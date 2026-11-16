"""The VEIL Core — the mandatory control layer between the Agent Runtime and
the Spell Engine.

Every spell execution passes through the VEIL Core, whatever invoked it (a
manual cast, an AI daemon, or a ritual). The Core is responsible for:

  * target-scope verification        (via the Authorization boundary)
  * execution-policy enforcement     (operational intensity)
  * resource management + rate limiting
  * operational-impact assessment    (a noise estimate)
  * telemetry collection
  * detection assessment             (how visible the test is to the TARGET's
                                      defenses — measured, never evaded)
  * audit-trail generation
  * emergency termination            (a hard kill-switch)

Design guarantees:
  * VEIL never conceals activity from Cybernomicon's own audit/telemetry —
    internal observability is always complete.
  * "Detection assessment" measures how noticeable an authorized test would be
    to the target's defenders; it does not implement monitoring evasion.
  * The kill-switch is authoritative: once armed, no new operation starts and
    in-flight operations are cancelled.
"""
from __future__ import annotations

import asyncio
import time
from typing import Callable

from ..authorization import Authorization
from .policy import VeilPolicy


class EmergencyStop(RuntimeError):
    """Raised by the gate when VEIL emergency termination is active."""


class VeilCore:
    def __init__(self, db, events,
                 authorization_provider: Callable[[], Authorization],
                 policy: VeilPolicy | None = None):
        self.db = db
        self.events = events
        self._auth_provider = authorization_provider
        self.policy = policy or VeilPolicy.for_intensity("normal")
        self._terminated = False
        self._sema = asyncio.Semaphore(self.policy.max_concurrency)
        self._last_op = 0.0
        self._delay_lock = asyncio.Lock()
        self._inflight: set[asyncio.Task] = set()
        self._counters = {"gated": 0, "halted": 0}

    # --- scope verification (centralized) ---------------------------------
    def authorization(self) -> Authorization:
        """The current authorization boundary — VEIL owns scope verification."""
        return self._auth_provider()

    # --- policy -----------------------------------------------------------
    def set_intensity(self, level: str) -> VeilPolicy:
        self.policy = VeilPolicy.for_intensity(level)
        self._sema = asyncio.Semaphore(self.policy.max_concurrency)
        self.events.emit("veil.policy", self.policy.as_dict())
        self.db.audit("veil.intensity", actor="operator",
                      detail=self.policy.as_dict())
        return self.policy

    # --- emergency termination --------------------------------------------
    @property
    def terminated(self) -> bool:
        return self._terminated

    def emergency_stop(self, actor: str = "operator") -> None:
        """Arm the kill-switch: block new ops and cancel in-flight ones."""
        self._terminated = True
        for task in list(self._inflight):
            task.cancel()
        self.events.emit("veil.halt", {"inflight_cancelled": len(self._inflight)})
        self.db.audit("veil.emergency_stop", actor=actor,
                      detail={"inflight": len(self._inflight)})

    def resume(self, actor: str = "operator") -> None:
        self._terminated = False
        self.events.emit("veil.resume", {})
        self.db.audit("veil.resume", actor=actor)

    # --- the gate (called by the engine before every op) ------------------
    async def gate(self) -> None:
        """Enforce the kill-switch, concurrency cap, and inter-op spacing."""
        if self._terminated:
            self._counters["halted"] += 1
            raise EmergencyStop("VEIL emergency termination is active")
        await self._sema.acquire()
        # Re-check after acquiring: a stop may have armed while we waited.
        if self._terminated:
            self._sema.release()
            self._counters["halted"] += 1
            raise EmergencyStop("VEIL emergency termination is active")
        if self.policy.inter_op_delay > 0:
            async with self._delay_lock:
                now = time.monotonic()
                wait = self._last_op + self.policy.inter_op_delay - now
                if wait > 0:
                    await asyncio.sleep(wait)
                self._last_op = time.monotonic()
        self._counters["gated"] += 1

    def release(self) -> None:
        self._sema.release()

    def track(self, task: asyncio.Task) -> None:
        self._inflight.add(task)

    def untrack(self, task: asyncio.Task) -> None:
        self._inflight.discard(task)

    # --- operational-impact / detection assessment ------------------------
    def assess_impact(self, spell_id: str, params: dict, result) -> dict:
        """Estimate how much network noise an operation made and how visible
        it would likely be to the TARGET's defenders (coverage measurement)."""
        hosts = 1
        ports = len(params.get("ports", []) or [])
        if isinstance(result, dict):
            hosts = int(result.get("hosts_scanned", hosts) or hosts)
            if not ports and "services" in result:
                ports = len(result.get("services", []))
        probes = max(hosts, 1) * max(ports, 1)
        # A simple, transparent heuristic. Higher = noisier = more likely to be
        # noticed by the target's monitoring.
        noise = float(probes)
        visibility = "low" if noise < 50 else "medium" if noise < 500 else "high"
        return {"noise_score": noise, "visibility": visibility,
                "hosts": hosts, "ports": ports, "probes": probes}

    def record_telemetry(self, execution_id: str, spell_id: str,
                         impact: dict) -> None:
        self.db.add_telemetry(execution_id=execution_id, spell_id=spell_id,
                              intensity=self.policy.intensity,
                              noise_score=impact["noise_score"],
                              visibility=impact["visibility"],
                              hosts=impact["hosts"], ports=impact["ports"])
        self.events.emit("veil.telemetry",
                         {"execution_id": execution_id, "spell_id": spell_id,
                          **impact, "intensity": self.policy.intensity})

    # --- status -----------------------------------------------------------
    def status(self) -> dict:
        return {
            "policy": self.policy.as_dict(),
            "terminated": self._terminated,
            "inflight": len(self._inflight),
            "counters": dict(self._counters),
        }
