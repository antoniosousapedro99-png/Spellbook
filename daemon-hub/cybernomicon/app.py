"""Application wiring: builds the shared hub state (db, engine, daemons).

Kept separate from the FastAPI layer so tests can construct a fully-functional
hub (engine + daemons + db) without an HTTP server.
"""
from __future__ import annotations

from dataclasses import dataclass

from . import spells as spell_library
from .authorization import Authorization
from .config import Settings
from .db import Database
from .daemons import DAEMON_CATALOGUE, build_daemon
from .daemons.base import DaemonReport
from .daemons.llm_planner import LLMPlanner
from .events import EventBroadcaster
from .familiar import Familiar
from .llm import OllamaClient
from .spell_engine import SpellEngine, registry
from .veil import VeilCore, VeilPolicy


@dataclass
class HubState:
    settings: Settings
    db: Database
    engine: SpellEngine
    events: EventBroadcaster
    veil: VeilCore

    def close(self) -> None:
        self.db.close()


def build_hub(settings: Settings) -> HubState:
    db = Database(settings.db_path)
    events = EventBroadcaster()

    # VEIL Core is a foundational dependency: construct it first and route the
    # engine through it so every operation is governed from the start.
    veil = VeilCore(
        db, events,
        authorization_provider=lambda: Authorization(db.authorized_targets()),
        policy=VeilPolicy.for_intensity(settings.veil_intensity))
    engine = SpellEngine(registry, db, events, veil=veil)

    # Bind the ARCHIVE spell to this database, then persist registry metadata
    # so the client's Grimoire can list installed spells.
    spell_library.bind_archive(db)
    engine.sync_registry_to_db()
    db.audit("hub.start", detail={"spells": len(registry),
                                  "veil_intensity": veil.policy.intensity})
    return HubState(settings=settings, db=db, engine=engine, events=events,
                    veil=veil)


def _ollama(settings: Settings) -> OllamaClient:
    return OllamaClient(settings.ollama_url, settings.ollama_model,
                        settings.llm_timeout)


def record_spell_effects(db: Database, spell_id: str, result) -> None:
    """Persist device-shaped results so the Astral Plane fills whether a spell
    was summoned via a daemon or cast directly. Safe to call repeatedly
    (upserts)."""
    if not isinstance(result, dict):
        return
    if spell_id == "scry":
        for dev in result.get("devices", []):
            db.upsert_device(dev["address"], hostname=dev.get("hostname"),
                             status=dev.get("status", "up"),
                             open_ports=dev.get("open_ports", []))
    elif spell_id == "identify":
        services = result.get("services", [])
        db.upsert_device(result["address"], attributes={"services": [
            {"port": s["port"], "guess": s["guess"]} for s in services]})


async def summon_daemon(hub: HubState, key: str, objective: str,
                        scope: str | None) -> DaemonReport:
    daemon = build_daemon(key, hub.engine, hub.db)

    # Phase III: optionally drive the daemon with a local LLM planner.
    if hub.settings.planner == "local":
        client = _ollama(hub.settings)
        if not await client.is_available():
            msg = (f"Local AI (Ollama) is not reachable at "
                   f"{hub.settings.ollama_url} with model "
                   f"'{hub.settings.ollama_model}'. Start `ollama serve` and "
                   f"`ollama pull {hub.settings.ollama_model}`, or set "
                   f"CYBERNOMICON_PLANNER=deterministic.")
            hub.db.audit("daemon.planner_unavailable", actor=key,
                         detail={"url": hub.settings.ollama_url})
            return DaemonReport(daemon=key, objective=objective, steps_run=0,
                                findings=[msg], summary=msg)
        specs = [s.spec() for s in hub.engine.registry.all()
                 if set(s.permissions) <= daemon.permissions]
        daemon.planner = LLMPlanner(client, daemon.display_name, specs,
                                    max_turns=hub.settings.llm_max_steps)
        daemon.max_steps = hub.settings.llm_max_steps

    hub.db.audit("daemon.summon", actor=key,
                 detail={"objective": objective, "scope": scope,
                         "planner": hub.settings.planner})
    hub.events.emit("daemon.summon",
                    {"daemon": key, "objective": objective, "scope": scope,
                     "planner": hub.settings.planner})
    report = await daemon.run(objective, {"scope": scope})
    hub.events.emit("daemon.report",
                    {"daemon": key, "summary": report.summary,
                     "steps": report.steps_run})
    return report


async def familiar_chat(hub: HubState, conversation_id: str | None,
                        message: str) -> dict:
    """One conversational turn with the Familiar (needs a reachable Ollama)."""
    client = _ollama(hub.settings)
    if not await client.is_available():
        reply = (f"Local AI (Ollama) is not reachable at "
                 f"{hub.settings.ollama_url} with model "
                 f"'{hub.settings.ollama_model}'. Start `ollama serve` and "
                 f"`ollama pull {hub.settings.ollama_model}` to wake the "
                 f"Familiar.")
        return {"conversation_id": conversation_id, "reply": reply, "trace": []}
    familiar = Familiar(hub.engine, hub.db, hub.events, client, hub.settings)
    return await familiar.chat(conversation_id, message)


async def planner_status(hub: HubState) -> dict:
    """Report the configured planner and, for local, Ollama reachability."""
    mode = hub.settings.planner
    info = {"planner": mode}
    if mode == "local":
        client = _ollama(hub.settings)
        info.update({
            "ollama_url": hub.settings.ollama_url,
            "model": hub.settings.ollama_model,
            "available": await client.is_available(),
        })
        try:
            info["models"] = await client.list_models()
        except Exception:  # noqa: BLE001
            info["models"] = []
    return info


__all__ = ["HubState", "build_hub", "summon_daemon", "familiar_chat",
           "planner_status", "record_spell_effects", "DAEMON_CATALOGUE"]
