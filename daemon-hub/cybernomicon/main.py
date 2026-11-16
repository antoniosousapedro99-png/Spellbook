"""FastAPI surface of the Daemon Hub.

Exposes the REST + WebSocket API the Android client consumes. All `/api/*` and
the WebSocket require the bearer token; `/health` is open for liveness checks.
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import (Depends, FastAPI, HTTPException, WebSocket,
                     WebSocketDisconnect)

from .app import (DAEMON_CATALOGUE, build_hub, familiar_chat, planner_status,
                  summon_daemon)
from .auth import TokenAuth, make_dependency
from .config import load_or_create_api_token, load_settings
from .models import (AddTargetRequest, CastSpellRequest, ChatRequest,
                     IntensityRequest, SummonDaemonRequest)
from .topology import build_topology
from .veil import INTENSITIES
from .spell_engine import SpellNotFound


def create_app() -> FastAPI:
    settings = load_settings()
    token = load_or_create_api_token(settings)
    auth = TokenAuth(token)
    hub = build_hub(settings)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield
        hub.close()

    app = FastAPI(title="Cybernomicon Daemon Hub", version="0.1.0",
                  lifespan=lifespan)
    app.state.hub = hub
    app.state.auth = auth
    require = Depends(make_dependency(auth))

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

    # --- Familiar: planner status + summon --------------------------------
    @app.get("/api/familiar/planner", dependencies=[require])
    async def planner() -> dict:
        return await planner_status(hub)

    @app.post("/api/familiar/chat", dependencies=[require])
    async def chat(body: ChatRequest) -> dict:
        return await familiar_chat(hub, body.conversation_id, body.message)

    @app.get("/api/familiar/conversations", dependencies=[require])
    async def conversations() -> dict:
        return {"conversations": hub.db.list_conversations()}

    @app.get("/api/familiar/conversations/{cid}", dependencies=[require])
    async def conversation(cid: str) -> dict:
        if not hub.db.conversation_exists(cid):
            raise HTTPException(404, "conversation not found")
        return {"conversation_id": cid, "messages": hub.db.list_messages(cid)}

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

    @app.get("/api/astral/topology", dependencies=[require])
    async def topology() -> dict:
        return build_topology(hub.db.list_devices(),
                              hub.db.authorized_targets())

    # --- VEIL: the mandatory control layer --------------------------------
    @app.get("/api/veil/status", dependencies=[require])
    async def veil_status() -> dict:
        return hub.veil.status()

    @app.post("/api/veil/intensity", dependencies=[require])
    async def veil_intensity(body: IntensityRequest) -> dict:
        if body.level not in INTENSITIES:
            raise HTTPException(
                400, f"unknown intensity: {body.level}; "
                     f"choose one of {sorted(INTENSITIES)}")
        return hub.veil.set_intensity(body.level).as_dict()

    @app.post("/api/veil/halt", dependencies=[require])
    async def veil_halt() -> dict:
        hub.veil.emergency_stop(actor="operator")
        return {"terminated": True}

    @app.post("/api/veil/resume", dependencies=[require])
    async def veil_resume() -> dict:
        hub.veil.resume(actor="operator")
        return {"terminated": False}

    @app.get("/api/veil/telemetry", dependencies=[require])
    async def veil_telemetry(limit: int = 100) -> dict:
        rows = hub.db.list_telemetry(limit=limit)
        summary = {
            "count": len(rows),
            "by_visibility": {
                v: sum(1 for r in rows if r["visibility"] == v)
                for v in ("low", "medium", "high")},
            "total_noise": round(sum(r["noise_score"] for r in rows), 2),
        }
        return {"telemetry": rows, "summary": summary}

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
