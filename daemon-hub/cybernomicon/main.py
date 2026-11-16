"""FastAPI surface of the Daemon Hub.

Exposes the REST + WebSocket API the Android client consumes. All `/api/*` and
the WebSocket require the bearer token; `/health` is open for liveness checks.
"""
from __future__ import annotations

import asyncio
import contextlib

from fastapi import (Depends, FastAPI, HTTPException, WebSocket,
                     WebSocketDisconnect)
from fastapi.responses import JSONResponse

from .app import DAEMON_CATALOGUE, build_hub, summon_daemon
from .auth import TokenAuth, make_dependency
from .config import load_or_create_api_token, load_settings
from .models import AddTargetRequest, CastSpellRequest, SummonDaemonRequest
from .spell_engine import SpellNotFound


def create_app() -> FastAPI:
    settings = load_settings()
    token = load_or_create_api_token(settings)
    auth = TokenAuth(token)
    hub = build_hub(settings)

    app = FastAPI(title="Cybernomicon Daemon Hub", version="0.1.0")
    app.state.hub = hub
    app.state.auth = auth
    require = Depends(make_dependency(auth))

    @app.on_event("shutdown")
    def _shutdown() -> None:
        hub.close()

    # --- health -----------------------------------------------------------
    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "service": "cybernomicon-daemon-hub",
                "spells": len(hub.engine.registry)}

    # --- Grimoire: spell + daemon discovery -------------------------------
    @app.get("/api/grimoire/spells", dependencies=[require])
    async def list_spells() -> dict:
        return {"spells": [s.spec() for s in hub.engine.registry.all()]}

    @app.get("/api/grimoire/daemons", dependencies=[require])
    async def list_daemons() -> dict:
        return {"daemons": DAEMON_CATALOGUE}

    # --- direct spell casting --------------------------------------------
    @app.post("/api/spells/{spell_id}/cast", dependencies=[require])
    async def cast_spell(spell_id: str, body: CastSpellRequest) -> dict:
        try:
            result = await hub.engine.execute(spell_id, body.params)
        except SpellNotFound:
            raise HTTPException(404, f"unknown spell: {spell_id}")
        return {
            "execution_id": result.execution_id,
            "status": result.status,
            "result": result.result,
            "error": result.error,
        }

    @app.get("/api/executions", dependencies=[require])
    async def executions(limit: int = 50) -> dict:
        return {"executions": hub.db.list_executions(limit=limit)}

    # --- Targets: the authorization boundary ------------------------------
    @app.get("/api/targets", dependencies=[require])
    async def list_targets() -> dict:
        return {"targets": hub.db.list_targets()}

    @app.post("/api/targets", dependencies=[require])
    async def add_target(body: AddTargetRequest) -> dict:
        row = hub.db.add_target(body.cidr, body.label, body.note)
        hub.db.audit("target.authorize", actor="client",
                     detail={"cidr": body.cidr})
        return {"target": row}

    @app.delete("/api/targets/{target_id}", dependencies=[require])
    async def remove_target(target_id: str) -> dict:
        ok = hub.db.remove_target(target_id)
        if not ok:
            raise HTTPException(404, "target not found")
        hub.db.audit("target.revoke", actor="client",
                     detail={"id": target_id})
        return {"removed": target_id}

    # --- Familiar: summon a daemon with an objective ----------------------
    @app.post("/api/daemons/{key}/summon", dependencies=[require])
    async def summon(key: str, body: SummonDaemonRequest) -> dict:
        try:
            report = await summon_daemon(hub, key, body.objective, body.scope)
        except KeyError:
            raise HTTPException(404, f"unknown daemon: {key}")
        return {
            "daemon": report.daemon,
            "objective": report.objective,
            "steps_run": report.steps_run,
            "findings": report.findings,
            "summary": report.summary,
            "executions": report.executions,
        }

    # --- Astral Plane: discovered devices ---------------------------------
    @app.get("/api/astral/devices", dependencies=[require])
    async def devices() -> dict:
        return {"devices": hub.db.list_devices()}

    # --- Chronicle: findings + audit --------------------------------------
    @app.get("/api/chronicle/findings", dependencies=[require])
    async def findings(limit: int = 100) -> dict:
        return {"findings": hub.db.list_findings(limit=limit)}

    @app.get("/api/chronicle/audit", dependencies=[require])
    async def audit(limit: int = 100) -> dict:
        return {"audit": hub.db.list_audit(limit=limit)}

    # --- live activity (WebSocket) ----------------------------------------
    @app.websocket("/ws/activity")
    async def activity(ws: WebSocket) -> None:
        token = ws.query_params.get("token")
        if not auth.verify_value(token):
            await ws.close(code=4401)
            return
        await ws.accept()
        q = hub.events.subscribe()
        try:
            while True:
                event = await q.get()
                await ws.send_json(event)
        except (WebSocketDisconnect, asyncio.CancelledError):
            pass
        finally:
            hub.events.unsubscribe(q)

    return app


app = create_app()


def main() -> None:  # pragma: no cover - entry point
    import uvicorn

    settings = load_settings()
    token = load_or_create_api_token(settings)
    scheme = "https" if settings.tls_enabled else "http"
    print("=" * 60)
    print(" Cybernomicon Daemon Hub")
    print(f"  listening on {scheme}://{settings.host}:{settings.port}")
    print(f"  API token:   {token}")
    if not settings.tls_enabled:
        print("  WARNING: TLS disabled. Set CYBERNOMICON_TLS_CERT/KEY for")
        print("           anything beyond a trusted local lab network.")
    print("=" * 60)
    uvicorn.run(app, host=settings.host, port=settings.port,
                ssl_certfile=settings.tls_certfile,
                ssl_keyfile=settings.tls_keyfile)


if __name__ == "__main__":  # pragma: no cover
    main()
