"""Daemon base class and the planner seam.

A *daemon* is an autonomous agent that pursues a natural-language objective by
selecting and running spells through the Spell Engine. In Phase I/II a daemon
uses a deterministic planner (no LLM) so the end-to-end slice is fully testable.
Phase III swaps in an LLM-backed planner behind the same `Planner` interface —
nothing else about the daemon changes.

Key design points:
  * Daemons never touch the network directly; they only call spells.
  * Daemons hold a permission set; the engine enforces it per spell.
  * Every step reports progress and is recorded (execution + audit).
  * Adaptive execution is bounded (max_steps) to prevent runaway loops.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from ..spell_engine import SpellEngine


@dataclass
class Step:
    """One planned action: run `spell_id` with `params`."""
    spell_id: str
    params: dict
    reason: str = ""


@dataclass
class Plan:
    steps: list[Step] = field(default_factory=list)


class Planner(Protocol):
    """Produces / revises a plan. Phase III provides an LLM implementation."""

    async def initial_plan(self, objective: str, context: dict) -> Plan: ...

    async def revise(self, objective: str, context: dict,
                     last_result: dict | None) -> Plan: ...


@dataclass
class DaemonReport:
    daemon: str
    objective: str
    steps_run: int
    findings: list[str]
    summary: str
    executions: list[str] = field(default_factory=list)


class Daemon:
    """Base orchestration loop shared by all daemons."""

    #: Stable identifier (e.g. "scryer").
    key: str = "daemon"
    #: Human-facing name.
    display_name: str = "Daemon"
    #: Permissions this daemon is granted; the engine checks each spell call.
    permissions: set[str] = set()
    #: VEIL-compatibility declarations (manifest requirement): what the daemon
    #: can do and what telemetry it is expected to produce.
    capabilities: tuple[str, ...] = ()
    expected_telemetry: tuple[str, ...] = ()
    #: Hard ceiling on adaptive planning iterations.
    max_steps: int = 16

    def __init__(self, engine: SpellEngine, planner: Planner):
        self.engine = engine
        self.planner = planner

    async def run(self, objective: str, context: dict | None = None
                  ) -> DaemonReport:
        context = dict(context or {})
        findings: list[str] = []
        executions: list[str] = []
        steps_run = 0
        last_result: dict | None = None

        try:
            plan = await self.planner.initial_plan(objective, context)
        except Exception as e:  # noqa: BLE001 - a planner failure is a finding
            return DaemonReport(
                daemon=self.key, objective=objective, steps_run=0,
                findings=[f"planner error: {e}"], executions=[],
                summary=f"{self.display_name} could not plan '{objective}': {e}")

        while plan.steps and steps_run < self.max_steps:
            step = plan.steps.pop(0)
            result = await self.engine.execute(
                step.spell_id, step.params,
                permissions=self.permissions, daemon=self.key)
            executions.append(result.execution_id)
            steps_run += 1

            if result.ok:
                last_result = {"spell": step.spell_id, "result": result.result}
                # Persist side effects (devices, etc.) regardless of planner —
                # this is the daemon's job, not the planner's, so the
                # deterministic and LLM planners behave identically.
                self.on_result(step, result.result)
                note = self.interpret(step, result.result)
                if note:
                    findings.append(note)
            else:
                last_result = {"spell": step.spell_id, "error": result.error,
                               "status": result.status}
                findings.append(
                    f"{step.spell_id} did not complete ({result.status}): "
                    f"{result.error}")

            # Let the planner adapt after every step: it may enqueue follow-up
            # actions (e.g. IDENTIFY per discovered host). The planner is
            # responsible for not re-queuing work (see ScryerPlanner's dedupe),
            # and max_steps bounds the loop. A planner failure ends the run
            # gracefully with a finding rather than crashing.
            try:
                revised = await self.planner.revise(
                    objective, context, last_result)
            except Exception as e:  # noqa: BLE001
                findings.append(f"planner error during execution: {e}")
                break
            plan.steps.extend(revised.steps)

        return DaemonReport(
            daemon=self.key, objective=objective, steps_run=steps_run,
            findings=findings, executions=executions,
            summary=self.summarize(objective, findings))

    # --- hooks subclasses override ----------------------------------------
    def on_result(self, step: Step, result: dict) -> None:
        """Persist side effects of a successful step (e.g. discovered devices).

        Runs for every planner, so persistence is independent of how the next
        step was chosen. Default: no-op.
        """
        return None

    def interpret(self, step: Step, result: dict) -> str | None:
        """Turn a successful spell result into a human-readable finding."""
        return None

    def summarize(self, objective: str, findings: list[str]) -> str:
        if not findings:
            return f"{self.display_name} completed '{objective}' with no findings."
        return (f"{self.display_name} completed '{objective}' with "
                f"{len(findings)} finding(s).")
