"""Tests for the VEIL Core control layer."""
import asyncio
import time

import pytest

from cybernomicon.authorization import Authorization
from cybernomicon.events import EventBroadcaster
from cybernomicon.spell_engine import SpellEngine, SpellRegistry
from cybernomicon.spell_engine.base import ExecutionContext, spell
from cybernomicon.veil import EmergencyStop, VeilCore, VeilPolicy


# --- policy ----------------------------------------------------------------

def test_intensity_presets():
    assert VeilPolicy.for_intensity("quiet").max_concurrency == 8
    assert VeilPolicy.for_intensity("active").max_concurrency == 256
    # Unknown intensity falls back to quiet, never something permissive.
    assert VeilPolicy.for_intensity("bogus").intensity == "quiet"


# --- impact assessment -----------------------------------------------------

def _veil(db):
    return VeilCore(db, EventBroadcaster(),
                    authorization_provider=lambda: Authorization([]),
                    policy=VeilPolicy.for_intensity("normal"))


def test_impact_scales_with_hosts_and_ports(db):
    v = _veil(db)
    low = v.assess_impact("scry", {"ports": [22]}, {"hosts_scanned": 3})
    high = v.assess_impact("scry", {"ports": list(range(10))},
                           {"hosts_scanned": 100})
    assert low["visibility"] == "low"
    assert high["visibility"] == "high"
    assert high["noise_score"] > low["noise_score"]


# --- gate: rate limiting + kill switch -------------------------------------

async def test_gate_enforces_inter_op_delay(db):
    v = VeilCore(db, EventBroadcaster(), lambda: Authorization([]),
                 VeilPolicy(intensity="slow", max_concurrency=4,
                            inter_op_delay=0.05, max_ops_per_run=100))
    t0 = time.monotonic()
    await v.gate(); v.release()
    await v.gate(); v.release()
    assert time.monotonic() - t0 >= 0.05


async def test_gate_blocks_when_terminated(db):
    v = _veil(db)
    v.emergency_stop()
    with pytest.raises(EmergencyStop):
        await v.gate()


# --- engine integration ----------------------------------------------------

@pytest.fixture
def veil_engine(db):
    reg = SpellRegistry()
    events = EventBroadcaster()

    @spell(id="ping", name="Ping", version="1.0.0", into=reg)
    async def _ping(params, ctx: ExecutionContext):
        return {"ok": True}

    @spell(id="slow", name="Slow", version="1.0.0", timeout_seconds=5,
           into=reg)
    async def _slow(params, ctx):
        await asyncio.sleep(2.0)
        return {"ok": True}

    veil = VeilCore(db, events, lambda: Authorization([]),
                    VeilPolicy.for_intensity("normal"))
    return SpellEngine(reg, db, events, veil=veil), veil


async def test_execute_records_telemetry(veil_engine, db):
    engine, _ = veil_engine
    res = await engine.execute("ping", {})
    assert res.ok
    tel = db.list_telemetry()
    assert tel and tel[0]["spell_id"] == "ping"
    assert tel[0]["visibility"] in ("low", "medium", "high")


async def test_emergency_stop_blocks_new_ops(veil_engine):
    engine, veil = veil_engine
    veil.emergency_stop()
    res = await engine.execute("ping", {})
    assert res.status == "halted"


async def test_emergency_stop_cancels_inflight(veil_engine):
    engine, veil = veil_engine
    task = asyncio.create_task(engine.execute("slow", {}))
    await asyncio.sleep(0.1)          # let it enter the handler
    veil.emergency_stop()             # cancel it mid-flight
    res = await task
    assert res.status == "halted"


async def test_resume_reenables(veil_engine):
    engine, veil = veil_engine
    veil.emergency_stop()
    assert (await engine.execute("ping", {})).status == "halted"
    veil.resume()
    assert (await engine.execute("ping", {})).ok


def test_set_intensity_updates_policy(db):
    v = _veil(db)
    v.set_intensity("quiet")
    assert v.policy.intensity == "quiet" and v.policy.inter_op_delay == 0.10
