"""Tests for the local-LLM (Ollama) planner, using a mock Ollama server.

A real model isn't needed: a tiny HTTP server stands in for Ollama and returns
scripted JSON decisions, letting us verify the planner parses decisions, builds
valid spell steps, fences untrusted results, and bounds itself — and that the
SCRYER daemon runs end-to-end driven by the LLM planner.
"""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from cybernomicon.daemons.llm_planner import LLMPlanner
from cybernomicon.daemons.scryer import build_scryer
from cybernomicon.db import Database
from cybernomicon.events import EventBroadcaster
from cybernomicon.llm import OllamaClient, OllamaError
from cybernomicon.spell_engine import SpellEngine, registry
import cybernomicon.spells as spell_library


def _make_server(script):
    """Start a mock Ollama server that returns `script` decisions in order."""
    class Handler(BaseHTTPRequestHandler):
        def _send(self, obj):
            data = json.dumps(obj).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/api/tags":
                self._send({"models": [{"name": "llama3.1:latest"}]})
            else:
                self.send_response(404); self.end_headers()

        def do_POST(self):
            ln = int(self.headers.get("Content-Length", 0))
            self.rfile.read(ln)
            i = self.server.counter
            self.server.counter += 1
            content = script[min(i, len(script) - 1)]
            self._send({"message": {"role": "assistant",
                                    "content": json.dumps(content)}})

        def log_message(self, *a):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.counter = 0
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_address[1]
    return server, f"http://127.0.0.1:{port}"


@pytest.fixture
async def listener():
    async def handle(reader, writer):
        writer.write(b"SSH-2.0-OpenSSH_TestLab\r\n")
        await writer.drain()
        writer.close()
    s = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = s.sockets[0].getsockname()[1]
    async with s:
        await s.start_serving()
        yield port


@pytest.fixture
def hub(tmp_path):
    db = Database(tmp_path / "llm.db")
    events = EventBroadcaster()
    engine = SpellEngine(registry, db, events)
    spell_library.bind_archive(db)
    engine.sync_registry_to_db()
    yield db, engine
    db.close()


async def test_client_unavailable_when_down():
    client = OllamaClient("http://127.0.0.1:9", "llama3.1")  # nothing there
    assert await client.is_available() is False


async def test_is_available_matches_exact_tag():
    # The mock advertises only 'llama3.1:latest'. A model that is NOT advertised
    # must report unavailable, even if it shares a family with something that
    # were installed (this is the llama3.2:3b-present / llama3.2:1b-missing bug).
    server, url = _make_server([{"done": True}])
    try:
        assert await OllamaClient(url, "llama3.2:1b").is_available() is False
        assert await OllamaClient(url, "llama3.2:3b").is_available() is False
        assert await OllamaClient(url, "llama3.1").is_available() is True
    finally:
        server.shutdown()


async def test_client_parses_json_decision():
    server, url = _make_server([{"reasoning": "r", "action": None, "done": True}])
    try:
        client = OllamaClient(url, "llama3.1")
        assert await client.is_available() is True
        out = await client.chat_json([{"role": "user", "content": "hi"}])
        assert out["done"] is True
    finally:
        server.shutdown()


async def test_planner_builds_step_and_bounds():
    # Always proposes scry; planner must stop at max_turns regardless.
    server, url = _make_server([
        {"reasoning": "discover", "action": {"spell": "scry",
         "params": {"scope": "127.0.0.1/32"}}, "done": False}])
    try:
        client = OllamaClient(url, "llama3.1")
        specs = [s.spec() for s in registry.all()]
        planner = LLMPlanner(client, "Scryer", specs, max_turns=3)
        plan = await planner.initial_plan("find hosts", {"scope": "127.0.0.1/32"})
        assert plan.steps[0].spell_id == "scry"
        # Drive revisions until it bounds out.
        for _ in range(5):
            plan = await planner.revise("find hosts", {"scope": "127.0.0.1/32"},
                                        {"spell": "scry", "result": {"x": 1}})
        assert planner._done and plan.steps == []
    finally:
        server.shutdown()


async def test_planner_rejects_unknown_spell():
    server, url = _make_server([
        {"reasoning": "nope", "action": {"spell": "rm_rf", "params": {}},
         "done": False}])
    try:
        client = OllamaClient(url, "llama3.1")
        planner = LLMPlanner(client, "Scryer",
                             [s.spec() for s in registry.all()])
        plan = await planner.initial_plan("x", {"scope": "127.0.0.1/32"})
        assert plan.steps == [] and planner._done
    finally:
        server.shutdown()


async def test_scryer_end_to_end_with_llm(hub, listener):
    db, engine = hub
    db.add_target("127.0.0.1/32", label="lab")
    # Scripted agent: scry -> identify the host -> archive -> done.
    script = [
        {"reasoning": "discover", "action": {"spell": "scry",
         "params": {"scope": "127.0.0.1/32", "ports": [listener]}},
         "done": False},
        {"reasoning": "fingerprint", "action": {"spell": "identify",
         "params": {"address": "127.0.0.1", "ports": [listener]}},
         "done": False},
        {"reasoning": "record", "action": {"spell": "archive",
         "params": {"kind": "discovery", "title": "LLM recon",
                    "severity": "info"}}, "done": False},
        {"reasoning": "complete", "action": None, "done": True},
    ]
    server, url = _make_server(script)
    try:
        client = OllamaClient(url, "llama3.1")
        specs = [s.spec() for s in registry.all()
                 if set(s.permissions) <= {"network.discover", "chronicle.write"}]
        planner = LLMPlanner(client, "Scryer", specs, max_turns=12)
        daemon = build_scryer(engine, db, planner=planner)
        daemon.max_steps = 12
        report = await daemon.run("inventory the lab", {"scope": "127.0.0.1/32"})

        # Device discovered + enriched via on_result (planner-independent).
        dev = db.get_device("127.0.0.1")
        assert dev is not None and listener in dev["open_ports"]
        assert dev["attributes"].get("services")
        # Chronicle got the archived finding.
        assert any(f["kind"] == "discovery" for f in db.list_findings())
        assert report.steps_run >= 3
    finally:
        server.shutdown()
