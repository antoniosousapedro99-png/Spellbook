"""SCRYER — the reconnaissance daemon.

SCRYER discovers and inventories devices on an authorized scope. For the
Phase I/II foundation it uses a deterministic planner:

    1. SCRY the scope to find live hosts.
    2. For each live host, IDENTIFY its services.
    3. ARCHIVE a discovery summary.

Results are written to the `devices` table (the Astral Plane) as they arrive.
The deterministic planner implements the same `Planner` interface the Phase III
LLM planner will, so swapping in real reasoning is a drop-in change.
"""
from __future__ import annotations

from ..db import Database
from ..spell_engine import SpellEngine
from .base import Daemon, Plan, Planner, Step


class ScryerPlanner:
    """Deterministic recon plan. No LLM — fully reproducible for testing."""

    def __init__(self, db: Database):
        self.db = db
        self._identified: set[str] = set()

    async def initial_plan(self, objective: str, context: dict) -> Plan:
        scope = context.get("scope")
        if not scope:
            return Plan(steps=[])
        params = {"scope": scope}
        if context.get("ports"):  # optional override (mainly for tests/labs)
            params["ports"] = context["ports"]
        return Plan(steps=[Step(
            spell_id="scry", params=params,
            reason="discover live hosts in the authorized scope")])

    async def revise(self, objective: str, context: dict,
                     last_result: dict | None) -> Plan:
        if not last_result or "result" not in last_result:
            return Plan(steps=[])

        spell = last_result["spell"]
        result = last_result["result"]

        if spell == "scry":
            # Persist discovered devices, then queue IDENTIFY for each.
            steps: list[Step] = []
            for dev in result.get("devices", []):
                self.db.upsert_device(
                    dev["address"], hostname=dev.get("hostname"),
                    status=dev["status"], open_ports=dev["open_ports"])
                if dev["open_ports"] and dev["address"] not in self._identified:
                    self._identified.add(dev["address"])
                    steps.append(Step(
                        spell_id="identify",
                        params={"address": dev["address"],
                                "ports": dev["open_ports"]},
                        reason=f"fingerprint services on {dev['address']}"))
            steps.append(Step(
                spell_id="archive",
                params={"kind": "discovery",
                        "title": f"Discovered {len(result.get('devices', []))} "
                                 f"device(s) in {result.get('scope')}",
                        "detail": ", ".join(d["address"]
                                            for d in result.get("devices", [])),
                        "severity": "info"},
                reason="record the discovery in the Chronicle"))
            return Plan(steps=steps)

        if spell == "identify":
            # Enrich the device record with service guesses.
            services = result.get("services", [])
            self.db.upsert_device(
                result["address"],
                attributes={"services": [
                    {"port": s["port"], "guess": s["guess"]} for s in services]})
            return Plan(steps=[])

        return Plan(steps=[])


class Scryer(Daemon):
    key = "scryer"
    display_name = "Scryer"
    permissions = {"network.discover", "chronicle.write"}
    max_steps = 64

    def interpret(self, step: Step, result: dict) -> str | None:
        if step.spell_id == "scry":
            n = len(result.get("devices", []))
            return f"Scry found {n} live host(s) in {result.get('scope')}."
        if step.spell_id == "identify":
            svcs = ", ".join(
                f"{s['port']}/{s['guess']}" for s in result.get("services", []))
            return f"Identified {result.get('address')}: {svcs}" if svcs else None
        return None

    def summarize(self, objective: str, findings: list[str]) -> str:
        hosts = sum(1 for f in findings if f.startswith("Scry found"))
        ident = sum(1 for f in findings if f.startswith("Identified"))
        return (f"Scryer finished reconnaissance for '{objective}': "
                f"{ident} device(s) fingerprinted.")


def build_scryer(engine: SpellEngine, db: Database) -> Scryer:
    return Scryer(engine, ScryerPlanner(db))
