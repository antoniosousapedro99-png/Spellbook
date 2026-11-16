"""Daemon registry.

Phase I ships one daemon (SCRYER). Daemons are constructed per-objective with
the shared engine + database, so the registry maps a key to a factory.
"""
from __future__ import annotations

from ..db import Database
from ..spell_engine import SpellEngine
from .base import Daemon, DaemonReport, Plan, Planner, Step
from .scryer import Scryer, build_scryer

DAEMON_FACTORIES = {
    "scryer": build_scryer,
}

DAEMON_CATALOGUE = [
    {"key": "scryer", "name": "Scryer", "role": "Reconnaissance",
     "description": "Discovers and inventories devices on authorized networks.",
     "permissions": sorted(Scryer.permissions)},
]


def build_daemon(key: str, engine: SpellEngine, db: Database) -> Daemon:
    if key not in DAEMON_FACTORIES:
        raise KeyError(f"unknown daemon: {key}")
    return DAEMON_FACTORIES[key](engine, db)


__all__ = ["Daemon", "DaemonReport", "Plan", "Planner", "Step",
           "build_daemon", "DAEMON_FACTORIES", "DAEMON_CATALOGUE"]
