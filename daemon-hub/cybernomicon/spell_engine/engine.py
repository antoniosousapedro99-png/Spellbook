"""The Spell Engine — validates, authorizes, executes, and records spells.

Execution pipeline for every spell call:

  1. resolve      — look up the spell in the registry
  2. permit       — check the caller holds the spell's required permissions
  3. validate in  — check params against the spell's JSON input schema
  4. record       — open an execution row (status=running) + audit entry
  5. run          — await the handler under a hard timeout
  6. validate out — check the result against the JSON output schema
  7. finalize     — record status/result/error/duration, emit completion event

No step can be skipped by an individual spell: spells only provide the handler
body for step 5. Authorization of network *targets* happens inside handlers via
`ctx.authorization`, because only the handler knows which addresses it will
touch — but the Authorization object is injected here, so a spell cannot forge
a wider scope.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

import jsonschema

from ..authorization import Authorization, AuthorizationError
from ..db import Database
from .base import EventSink, ExecutionContext, Spell, SpellRegistry


class PermissionDenied(PermissionError):
    pass


class SpellNotFound(KeyError):
    pass


@dataclass
class ExecutionResult:
    execution_id: str
    spell_id: str
    status: str                 # success | error | timeout | denied
    result: Any = None
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.status == "success"


class SpellEngine:
    def __init__(self, registry: SpellRegistry, db: Database,
                 events: EventSink):
        self.registry = registry
        self.db = db
        self.events = events

    def sync_registry_to_db(self) -> None:
        """Persist installed-spell metadata so the client can list them."""
        for sp in self.registry.all():
            self.db.upsert_spell(sp.spec())

    async def execute(self, spell_id: str, params: dict | None = None, *,
                      permissions: set[str] | None = None,
                      daemon: str | None = None) -> ExecutionResult:
        params = params or {}
        granted = permissions if permissions is not None else {"*"}

        spell = self.registry.get(spell_id)
        if spell is None:
            raise SpellNotFound(spell_id)

        # --- permission check ---------------------------------------------
        missing = self._missing_permissions(spell, granted)
        if missing:
            exec_id = self.db.start_execution(spell_id, params, daemon)
            msg = f"missing permissions: {sorted(missing)}"
            self.db.finish_execution(exec_id, "denied", error=msg)
            self.db.audit("spell.denied", actor=daemon or "client",
                          detail={"spell": spell_id, "missing": sorted(missing)})
            self.events.emit("spell.denied",
                             {"execution_id": exec_id, "spell_id": spell_id,
                              "error": msg})
            return ExecutionResult(exec_id, spell_id, "denied", error=msg)

        # --- input validation ---------------------------------------------
        try:
            if spell.input_schema:
                jsonschema.validate(params, spell.input_schema)
        except jsonschema.ValidationError as e:
            exec_id = self.db.start_execution(spell_id, params, daemon)
            msg = f"invalid input: {e.message}"
            self.db.finish_execution(exec_id, "error", error=msg)
            self.events.emit("spell.error",
                             {"execution_id": exec_id, "spell_id": spell_id,
                              "error": msg})
            return ExecutionResult(exec_id, spell_id, "error", error=msg)

        # --- record + run --------------------------------------------------
        exec_id = self.db.start_execution(spell_id, params, daemon)
        auth = Authorization(self.db.authorized_targets())
        ctx = ExecutionContext(
            execution_id=exec_id, spell_id=spell_id, authorization=auth,
            events=self.events, daemon=daemon)
        self.db.audit("spell.start", actor=daemon or "client",
                      detail={"spell": spell_id, "execution": exec_id})
        self.events.emit("spell.start",
                         {"execution_id": exec_id, "spell_id": spell_id,
                          "daemon": daemon})

        try:
            result = await asyncio.wait_for(
                spell.handler(params, ctx), timeout=spell.timeout_seconds)
        except asyncio.TimeoutError:
            msg = f"spell timed out after {spell.timeout_seconds}s"
            self.db.finish_execution(exec_id, "timeout", error=msg)
            self.events.emit("spell.timeout",
                             {"execution_id": exec_id, "spell_id": spell_id})
            return ExecutionResult(exec_id, spell_id, "timeout", error=msg)
        except AuthorizationError as e:
            msg = f"authorization denied: {e}"
            self.db.finish_execution(exec_id, "denied", error=msg)
            self.db.audit("spell.denied", actor=daemon or "client",
                          detail={"spell": spell_id, "reason": str(e)})
            self.events.emit("spell.denied",
                             {"execution_id": exec_id, "spell_id": spell_id,
                              "error": msg})
            return ExecutionResult(exec_id, spell_id, "denied", error=msg)
        except Exception as e:  # noqa: BLE001 - spells must never crash the hub
            msg = f"{type(e).__name__}: {e}"
            self.db.finish_execution(exec_id, "error", error=msg)
            self.events.emit("spell.error",
                             {"execution_id": exec_id, "spell_id": spell_id,
                              "error": msg})
            return ExecutionResult(exec_id, spell_id, "error", error=msg)

        # --- output validation --------------------------------------------
        try:
            if spell.output_schema:
                jsonschema.validate(result, spell.output_schema)
        except jsonschema.ValidationError as e:
            msg = f"spell produced invalid output: {e.message}"
            self.db.finish_execution(exec_id, "error", error=msg)
            self.events.emit("spell.error",
                             {"execution_id": exec_id, "spell_id": spell_id,
                              "error": msg})
            return ExecutionResult(exec_id, spell_id, "error", error=msg)

        self.db.finish_execution(exec_id, "success", result=result)
        self.events.emit("spell.success",
                         {"execution_id": exec_id, "spell_id": spell_id})
        return ExecutionResult(exec_id, spell_id, "success", result=result)

    @staticmethod
    def _missing_permissions(spell: Spell, granted: set[str]) -> set[str]:
        if "*" in granted:
            return set()
        return set(spell.permissions) - granted
