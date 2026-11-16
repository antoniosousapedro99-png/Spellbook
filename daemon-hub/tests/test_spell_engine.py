import asyncio

import pytest

from cybernomicon.spell_engine import SpellEngine, SpellNotFound, SpellRegistry
from cybernomicon.spell_engine.base import ExecutionContext, spell


@pytest.fixture
def engine(db, events):
    reg = SpellRegistry()

    @spell(id="echo", name="Echo", version="1.0.0",
           input_schema={"type": "object",
                         "properties": {"msg": {"type": "string"}},
                         "required": ["msg"], "additionalProperties": False},
           output_schema={"type": "object",
                          "properties": {"echo": {"type": "string"}},
                          "required": ["echo"]},
           into=reg)
    async def _echo(params, ctx: ExecutionContext):
        ctx.progress("echoing")
        return {"echo": params["msg"]}

    @spell(id="secret", name="Secret", version="1.0.0",
           permissions=("admin",), into=reg)
    async def _secret(params, ctx):
        return {"ok": True}

    @spell(id="slow", name="Slow", version="1.0.0", timeout_seconds=0.1,
           into=reg)
    async def _slow(params, ctx):
        await asyncio.sleep(1.0)
        return {"ok": True}

    @spell(id="bad_output", name="BadOutput", version="1.0.0",
           output_schema={"type": "object",
                          "properties": {"n": {"type": "integer"}},
                          "required": ["n"]},
           into=reg)
    async def _bad(params, ctx):
        return {"n": "not-an-integer"}

    @spell(id="boom", name="Boom", version="1.0.0", into=reg)
    async def _boom(params, ctx):
        raise ValueError("kaboom")

    return SpellEngine(reg, db, events)


async def test_success(engine):
    res = await engine.execute("echo", {"msg": "hi"})
    assert res.ok and res.result == {"echo": "hi"}


async def test_unknown_spell(engine):
    with pytest.raises(SpellNotFound):
        await engine.execute("nope", {})


async def test_input_validation(engine):
    res = await engine.execute("echo", {"wrong": 1})
    assert res.status == "error" and "invalid input" in res.error


async def test_permission_denied(engine):
    res = await engine.execute("secret", {}, permissions=set())
    assert res.status == "denied" and "admin" in res.error


async def test_permission_granted(engine):
    res = await engine.execute("secret", {}, permissions={"admin"})
    assert res.ok


async def test_timeout(engine):
    res = await engine.execute("slow", {})
    assert res.status == "timeout"


async def test_output_validation(engine):
    res = await engine.execute("bad_output", {})
    assert res.status == "error" and "invalid output" in res.error


async def test_handler_exception_is_contained(engine):
    res = await engine.execute("boom", {})
    assert res.status == "error" and "kaboom" in res.error


async def test_execution_is_recorded(engine, db):
    await engine.execute("echo", {"msg": "recorded"})
    rows = db.list_executions()
    assert rows and rows[0]["spell_id"] == "echo"
    assert rows[0]["status"] == "success"
    assert rows[0]["duration_ms"] is not None
