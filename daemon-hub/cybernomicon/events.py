"""Live event broadcaster.

Implements the `EventSink` protocol the Spell Engine uses. Every emitted event
is (a) kept in a bounded ring buffer so late-joining clients get recent history
and (b) fanned out to all connected WebSocket subscribers via per-connection
asyncio queues.

`emit()` is synchronous and is called from within the event loop (the engine is
async), so `put_nowait` onto each queue is safe and non-blocking. If a slow
client's queue fills, its oldest events are dropped rather than blocking the
engine.
"""
from __future__ import annotations

import asyncio
import time
from collections import deque


class EventBroadcaster:
    def __init__(self, history: int = 200, queue_size: int = 256):
        self._subscribers: set[asyncio.Queue] = set()
        self._history: deque[dict] = deque(maxlen=history)
        self._queue_size = queue_size

    def emit(self, event_type: str, payload: dict) -> None:
        event = {"type": event_type, "ts": time.time(), **payload}
        self._history.append(event)
        for q in list(self._subscribers):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                try:
                    q.get_nowait()        # drop oldest
                    q.put_nowait(event)
                except Exception:
                    pass

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=self._queue_size)
        # Prime with recent history so a fresh UI isn't blank.
        for event in self._history:
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                break
        self._subscribers.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subscribers.discard(q)

    def recent(self) -> list[dict]:
        return list(self._history)
