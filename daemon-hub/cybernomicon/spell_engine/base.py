"""Spell definitions, the execution context, and the spell registry.

A *spell* is a single executable capability with a declared contract:
identifier, version, input/output JSON schemas, required permissions, a timeout,
documentation, and an async handler. Spells are pure capability units — they
know nothing about daemons, HTTP, or the database except through the
`ExecutionContext` handed to them at run time.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Awaitable, Callable, Protocol

from ..authorization import Authorization
from ..safety import Untrusted, tag_untrusted


class EventSink(Protocol):
    """Receives live progress events (wired to WebSocket + audit log)."""

    def emit(self, event_type: str, payload: dict) -> None: ...


@dataclass
class ExecutionContext:
    """Everything a spell is allowed to touch while executing.

    Spells receive capabilities through this object rather than importing
    globals, which keeps them testable and keeps the security boundary
    (authorization, auditing, event emission) in one place.
    """

    execution_id: str
    spell_id: str
    authorization: Authorization
    events: EventSink
    daemon: str | None = None
    # Observations the spell gathered from untrusted sources, collected here so
    # callers (and the Phase III agent runtime) can fence them appropriately.
    observations: list[Untrusted] = field(default_factory=list)

    def progress(self, message: str, **data) -> None:
        self.events.emit("spell.progress", {
            "execution_id": self.execution_id,
            "spell_id": self.spell_id,
            "message": message,
            **data,
        })

    def observe(self, value: str, source: str) -> Untrusted:
        """Record a value observed from an untrusted source."""
        u = tag_untrusted(value, source)
        self.observations.append(u)
        return u


SpellHandler = Callable[[dict, ExecutionContext], Awaitable[dict]]


@dataclass(frozen=True)
class Spell:
    """A registered, executable capability."""

    id: str
    name: str
    version: str
    handler: SpellHandler
    description: str = ""
    permissions: tuple[str, ...] = ()
    input_schema: dict = field(default_factory=dict)
    output_schema: dict = field(default_factory=dict)
    timeout_seconds: float = 30.0
    documentation: str = ""

    def spec(self) -> dict:
        """Serializable metadata for the registry / discovery endpoint."""
        return {
            "id": self.id,
            "name": self.name,
            "version": self.version,
            "description": self.description,
            "permissions": list(self.permissions),
            "input_schema": self.input_schema,
            "output_schema": self.output_schema,
            "timeout_seconds": self.timeout_seconds,
            "documentation": self.documentation,
        }


class SpellRegistry:
    """Central catalogue of installed spells.

    Supports registration (via the `@spell` decorator or `register`) and
    discovery (`all`, `get`). The engine and the discovery API read from here.
    """

    def __init__(self) -> None:
        self._spells: dict[str, Spell] = {}

    def register(self, spell: Spell) -> Spell:
        if spell.id in self._spells:
            raise ValueError(f"spell id already registered: {spell.id}")
        self._spells[spell.id] = spell
        return spell

    def get(self, spell_id: str) -> Spell | None:
        return self._spells.get(spell_id)

    def all(self) -> list[Spell]:
        return [self._spells[k] for k in sorted(self._spells)]

    def __len__(self) -> int:  # pragma: no cover - trivial
        return len(self._spells)


# Process-wide registry. Spells register into it at import time; the engine is
# constructed with it. Tests can build their own registry for isolation.
registry = SpellRegistry()


def spell(*, id: str, name: str, version: str, description: str = "",
          permissions: tuple[str, ...] = (), input_schema: dict | None = None,
          output_schema: dict | None = None, timeout_seconds: float = 30.0,
          documentation: str = "", into: SpellRegistry | None = None):
    """Decorator that turns an async function into a registered Spell."""

    def decorator(func: SpellHandler) -> Spell:
        sp = Spell(
            id=id, name=name, version=version, handler=func,
            description=description, permissions=tuple(permissions),
            input_schema=input_schema or {}, output_schema=output_schema or {},
            timeout_seconds=timeout_seconds,
            documentation=documentation or (func.__doc__ or "").strip(),
        )
        (into if into is not None else registry).register(sp)
        return sp

    return decorator
