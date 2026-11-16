"""SQLite persistence layer for the Daemon Hub.

A single-file SQLite database is deliberate for the Phase I foundation: zero
operational overhead, transactional, and trivially portable. The access layer
is intentionally thin and synchronous — SQLite writes are fast and the hub
runs them inside a thread-aware connection. If a future phase needs Postgres,
every query lives here behind the `Database` facade.

Schema (see ARCHITECTURE.md for the rationale):

  targets        Explicitly authorized scan scopes (the authorization boundary).
  spells         Registry of installed spells and their metadata.
  executions     Every spell execution with inputs, outputs, status, timing.
  devices        Discovered network devices (the Astral Plane data).
  findings       Interpreted results / security observations (the Chronicle).
  audit_log      Append-only record of consequential actions.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS targets (
    id          TEXT PRIMARY KEY,
    cidr        TEXT NOT NULL,
    label       TEXT NOT NULL DEFAULT '',
    authorized  INTEGER NOT NULL DEFAULT 1,
    note        TEXT NOT NULL DEFAULT '',
    created_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS spells (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    version         TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    permissions     TEXT NOT NULL DEFAULT '[]',
    input_schema    TEXT NOT NULL DEFAULT '{}',
    output_schema   TEXT NOT NULL DEFAULT '{}',
    timeout_seconds REAL NOT NULL DEFAULT 30,
    documentation   TEXT NOT NULL DEFAULT '',
    registered_at   REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS executions (
    id           TEXT PRIMARY KEY,
    spell_id     TEXT NOT NULL,
    daemon       TEXT,
    status       TEXT NOT NULL,          -- pending|running|success|error|timeout|denied
    params       TEXT NOT NULL DEFAULT '{}',
    result       TEXT,
    error        TEXT,
    started_at   REAL NOT NULL,
    finished_at  REAL,
    duration_ms  INTEGER
);

CREATE TABLE IF NOT EXISTS devices (
    id            TEXT PRIMARY KEY,
    address       TEXT NOT NULL UNIQUE,
    hostname      TEXT,
    mac           TEXT,
    vendor        TEXT,
    status        TEXT NOT NULL DEFAULT 'up',
    open_ports    TEXT NOT NULL DEFAULT '[]',
    attributes    TEXT NOT NULL DEFAULT '{}',
    first_seen    REAL NOT NULL,
    last_seen     REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS findings (
    id           TEXT PRIMARY KEY,
    kind         TEXT NOT NULL,          -- discovery|observation|vulnerability|summary
    severity     TEXT NOT NULL DEFAULT 'info',
    title        TEXT NOT NULL,
    detail       TEXT NOT NULL DEFAULT '',
    device_id    TEXT,
    execution_id TEXT,
    daemon       TEXT,
    created_at   REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         TEXT PRIMARY KEY,
    action     TEXT NOT NULL,
    actor      TEXT NOT NULL DEFAULT 'system',
    detail     TEXT NOT NULL DEFAULT '{}',
    created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    role            TEXT NOT NULL,          -- user|assistant|tool
    content         TEXT NOT NULL DEFAULT '',
    created_at      REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_conv
    ON messages (conversation_id, created_at);
"""


def _now() -> float:
    return time.time()


def _uid() -> str:
    return uuid.uuid4().hex


class Database:
    """Thin, thread-safe facade over a SQLite connection."""

    def __init__(self, path: Path | str):
        self.path = str(path)
        # check_same_thread=False + a lock lets FastAPI's threadpool share one
        # connection safely without per-request connection churn.
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.execute("PRAGMA foreign_keys=ON;")
        self._lock = threading.Lock()
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # --- low-level helpers -------------------------------------------------
    @staticmethod
    def _bind(params: Any) -> Any:
        # Preserve dicts for named (:name) placeholders; tuple-ify sequences
        # for positional (?) placeholders.
        return params if isinstance(params, Mapping) else tuple(params)

    def _execute(self, sql: str, params: Iterable[Any] = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, self._bind(params))
            self._conn.commit()
            return cur

    def _query(self, sql: str, params: Iterable[Any] = ()) -> list[sqlite3.Row]:
        with self._lock:
            return list(self._conn.execute(sql, self._bind(params)).fetchall())

    # --- targets / authorization ------------------------------------------
    def add_target(self, cidr: str, label: str = "", note: str = "",
                   authorized: bool = True) -> dict:
        row = {
            "id": _uid(), "cidr": cidr, "label": label, "note": note,
            "authorized": 1 if authorized else 0, "created_at": _now(),
        }
        self._execute(
            "INSERT INTO targets (id,cidr,label,authorized,note,created_at) "
            "VALUES (:id,:cidr,:label,:authorized,:note,:created_at)", row)
        return row

    def list_targets(self) -> list[dict]:
        return [dict(r) for r in self._query(
            "SELECT * FROM targets ORDER BY created_at")]

    def authorized_targets(self) -> list[str]:
        return [r["cidr"] for r in self._query(
            "SELECT cidr FROM targets WHERE authorized=1")]

    def remove_target(self, target_id: str) -> bool:
        cur = self._execute("DELETE FROM targets WHERE id=?", (target_id,))
        return cur.rowcount > 0

    # --- spell registry ----------------------------------------------------
    def upsert_spell(self, spec: dict) -> None:
        self._execute(
            "INSERT INTO spells (id,name,version,description,permissions,"
            "input_schema,output_schema,timeout_seconds,documentation,registered_at) "
            "VALUES (:id,:name,:version,:description,:permissions,:input_schema,"
            ":output_schema,:timeout_seconds,:documentation,:registered_at) "
            "ON CONFLICT(id) DO UPDATE SET name=excluded.name,version=excluded.version,"
            "description=excluded.description,permissions=excluded.permissions,"
            "input_schema=excluded.input_schema,output_schema=excluded.output_schema,"
            "timeout_seconds=excluded.timeout_seconds,documentation=excluded.documentation",
            {
                "id": spec["id"], "name": spec["name"], "version": spec["version"],
                "description": spec.get("description", ""),
                "permissions": json.dumps(spec.get("permissions", [])),
                "input_schema": json.dumps(spec.get("input_schema", {})),
                "output_schema": json.dumps(spec.get("output_schema", {})),
                "timeout_seconds": spec.get("timeout_seconds", 30),
                "documentation": spec.get("documentation", ""),
                "registered_at": _now(),
            })

    def list_spells(self) -> list[dict]:
        out = []
        for r in self._query("SELECT * FROM spells ORDER BY id"):
            d = dict(r)
            for k in ("permissions", "input_schema", "output_schema"):
                d[k] = json.loads(d[k])
            out.append(d)
        return out

    # --- executions --------------------------------------------------------
    def start_execution(self, spell_id: str, params: dict,
                        daemon: str | None = None) -> str:
        exec_id = _uid()
        self._execute(
            "INSERT INTO executions (id,spell_id,daemon,status,params,started_at) "
            "VALUES (?,?,?,?,?,?)",
            (exec_id, spell_id, daemon, "running", json.dumps(params), _now()))
        return exec_id

    def finish_execution(self, exec_id: str, status: str,
                        result: Any = None, error: str | None = None) -> None:
        with self._lock:
            row = self._conn.execute(
                "SELECT started_at FROM executions WHERE id=?",
                (exec_id,)).fetchone()
            started = row["started_at"] if row else _now()
            finished = _now()
            self._conn.execute(
                "UPDATE executions SET status=?,result=?,error=?,finished_at=?,"
                "duration_ms=? WHERE id=?",
                (status, json.dumps(result) if result is not None else None,
                 error, finished, int((finished - started) * 1000), exec_id))
            self._conn.commit()

    def list_executions(self, limit: int = 50) -> list[dict]:
        out = []
        for r in self._query(
                "SELECT * FROM executions ORDER BY started_at DESC LIMIT ?",
                (limit,)):
            d = dict(r)
            d["params"] = json.loads(d["params"]) if d["params"] else {}
            d["result"] = json.loads(d["result"]) if d["result"] else None
            out.append(d)
        return out

    # --- devices (Astral Plane) -------------------------------------------
    def upsert_device(self, address: str, **fields: Any) -> dict:
        existing = self._query(
            "SELECT * FROM devices WHERE address=?", (address,))
        now = _now()
        json_fields = {"open_ports", "attributes"}
        if existing:
            dev = dict(existing[0])
            sets, vals = [], []
            for k, v in fields.items():
                if v is None:
                    continue
                sets.append(f"{k}=?")
                vals.append(json.dumps(v) if k in json_fields else v)
            sets.append("last_seen=?")
            vals.append(now)
            vals.append(dev["id"])
            self._execute(
                f"UPDATE devices SET {','.join(sets)} WHERE id=?", vals)
        else:
            dev_id = _uid()
            self._execute(
                "INSERT INTO devices (id,address,hostname,mac,vendor,status,"
                "open_ports,attributes,first_seen,last_seen) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                (dev_id, address, fields.get("hostname"), fields.get("mac"),
                 fields.get("vendor"), fields.get("status", "up"),
                 json.dumps(fields.get("open_ports", [])),
                 json.dumps(fields.get("attributes", {})), now, now))
        return self.get_device(address)

    def get_device(self, address: str) -> dict | None:
        rows = self._query("SELECT * FROM devices WHERE address=?", (address,))
        if not rows:
            return None
        d = dict(rows[0])
        d["open_ports"] = json.loads(d["open_ports"])
        d["attributes"] = json.loads(d["attributes"])
        return d

    def list_devices(self) -> list[dict]:
        out = []
        for r in self._query("SELECT * FROM devices ORDER BY address"):
            d = dict(r)
            d["open_ports"] = json.loads(d["open_ports"])
            d["attributes"] = json.loads(d["attributes"])
            out.append(d)
        return out

    # --- findings (Chronicle) ---------------------------------------------
    def add_finding(self, kind: str, title: str, detail: str = "",
                   severity: str = "info", device_id: str | None = None,
                   execution_id: str | None = None,
                   daemon: str | None = None) -> dict:
        row = {
            "id": _uid(), "kind": kind, "severity": severity, "title": title,
            "detail": detail, "device_id": device_id,
            "execution_id": execution_id, "daemon": daemon, "created_at": _now(),
        }
        self._execute(
            "INSERT INTO findings (id,kind,severity,title,detail,device_id,"
            "execution_id,daemon,created_at) VALUES (:id,:kind,:severity,:title,"
            ":detail,:device_id,:execution_id,:daemon,:created_at)", row)
        return row

    def list_findings(self, limit: int = 100) -> list[dict]:
        return [dict(r) for r in self._query(
            "SELECT * FROM findings ORDER BY created_at DESC LIMIT ?", (limit,))]

    # --- audit log ---------------------------------------------------------
    def audit(self, action: str, actor: str = "system",
              detail: dict | None = None) -> None:
        self._execute(
            "INSERT INTO audit_log (id,action,actor,detail,created_at) "
            "VALUES (?,?,?,?,?)",
            (_uid(), action, actor, json.dumps(detail or {}), _now()))

    def list_audit(self, limit: int = 100) -> list[dict]:
        out = []
        for r in self._query(
                "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ?",
                (limit,)):
            d = dict(r)
            d["detail"] = json.loads(d["detail"])
            out.append(d)
        return out

    # --- Familiar conversations -------------------------------------------
    def create_conversation(self, title: str = "") -> str:
        cid = _uid()
        now = _now()
        self._execute(
            "INSERT INTO conversations (id,title,created_at,updated_at) "
            "VALUES (?,?,?,?)", (cid, title, now, now))
        return cid

    def conversation_exists(self, cid: str) -> bool:
        return bool(self._query(
            "SELECT 1 FROM conversations WHERE id=?", (cid,)))

    def add_message(self, conversation_id: str, role: str, content: str) -> dict:
        row = {"id": _uid(), "conversation_id": conversation_id, "role": role,
               "content": content, "created_at": _now()}
        self._execute(
            "INSERT INTO messages (id,conversation_id,role,content,created_at) "
            "VALUES (:id,:conversation_id,:role,:content,:created_at)", row)
        self._execute("UPDATE conversations SET updated_at=? WHERE id=?",
                      (row["created_at"], conversation_id))
        return row

    def list_messages(self, conversation_id: str) -> list[dict]:
        return [dict(r) for r in self._query(
            "SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at",
            (conversation_id,))]

    def list_conversations(self, limit: int = 50) -> list[dict]:
        return [dict(r) for r in self._query(
            "SELECT * FROM conversations ORDER BY updated_at DESC LIMIT ?",
            (limit,))]
