"""Application wiring: builds the shared hub state (db, engine, daemons).

Kept separate from the FastAPI layer so tests can construct a fully-functional
hub (engine + daemons + db) without an HTTP server.
"""
from __future__ import annotations

from dataclasses import dataclass

from . import spells as spell_library
from .config import Settings
from .db import Database
from .daemons import DAEMON_CATALOGUE, build_daemon
from .daemons.base import DaemonReport
from .events import EventBroadcaster
from .spell_engine import SpellEngine, registry


@dataclass
class HubState:
    settings: Settings
    db: Database
    engine: SpellEngine
    events: EventBroadcaster

    def close(self) -> None:
        self.db.close()


def build_hub(settings: Settings) -> HubState:
    db = Database(settings.db_path)
    events = EventBroadcaster()
    engine = SpellEngine(registry, db, events)

    # Bind the ARCHIVE spell to this database, then persist registry metadata
    # so the client's Grimoire can list installed spells.
    spell_library.bind_archive(db)
    engine.sync_registry_to_db()
    db.audit("hub.start", detail={"spells": len(registry)})
    return HubState(settings=settings, db=db, engine=engine, events=events)


async def summon_daemon(hub: HubState, key: str, objective: str,
                        scope: str | None) -> DaemonReport:
    daemon = build_daemon(key, hub.engine, hub.db)
    hub.db.audit("daemon.summon", actor=key,
                 detail={"objective": objective, "scope": scope})
    hub.events.emit("daemon.summon",
                    {"daemon": key, "objective": objective, "scope": scope})
    report = await daemon.run(objective, {"scope": scope})
    hub.events.emit("daemon.report",
                    {"daemon": key, "summary": report.summary,
                     "steps": report.steps_run})
    return report


__all__ = ["HubState", "build_hub", "summon_daemon", "DAEMON_CATALOGUE"]
