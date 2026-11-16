"""ARCHIVE — persist a finding into the Chronicle.

A utility spell that daemons use to record a structured observation or summary.
It performs no network activity, so it needs no target authorization — only the
`chronicle.write` permission.
"""
from __future__ import annotations

from ..spell_engine.base import ExecutionContext
from ..spell_engine import spell
from ..safety import scrub

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "kind": {"type": "string",
                  "enum": ["discovery", "observation", "vulnerability",
                           "summary"]},
        "title": {"type": "string", "minLength": 1},
        "detail": {"type": "string"},
        "severity": {"type": "string",
                      "enum": ["info", "low", "medium", "high", "critical"]},
    },
    "required": ["kind", "title"],
    "additionalProperties": False,
}

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {"finding_id": {"type": "string"}},
    "required": ["finding_id"],
}

# ARCHIVE needs a handle to the database. It is bound at engine-setup time via
# `bind_archive(db)` so the spell stays import-safe and testable.
_db = None


def bind_archive(db) -> None:
    global _db
    _db = db


@spell(
    id="archive",
    name="Archive",
    version="1.0.0",
    description="Store a finding or summary in the Chronicle.",
    permissions=("chronicle.write",),
    input_schema=INPUT_SCHEMA,
    output_schema=OUTPUT_SCHEMA,
    timeout_seconds=10.0,
    documentation="Records a structured finding in persistent history.",
)
async def archive(params: dict, ctx: ExecutionContext) -> dict:
    if _db is None:  # pragma: no cover - defensive
        raise RuntimeError("archive spell not bound to a database")
    finding = _db.add_finding(
        kind=params["kind"],
        title=scrub(params["title"], 256),
        detail=scrub(params.get("detail", ""), 4096),
        severity=params.get("severity", "info"),
        execution_id=ctx.execution_id,
        daemon=ctx.daemon,
    )
    ctx.progress(f"archived finding: {finding['title']}")
    return {"finding_id": finding["id"]}
