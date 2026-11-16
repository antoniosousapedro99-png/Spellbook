"""Tests for the conversational Familiar, driven by a mock Ollama server."""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from cybernomicon.config import load_settings
from cybernomicon.db import Database
from cybernomicon.events import EventBroadcaster
from cybernomicon.familiar import Familiar
from cybernomicon.llm import OllamaClient
from cybernomicon.spell_engine import SpellEngine, registry
import cybernomicon.spells as spell_library


def _make_server(script):
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
            self.rfile.read(int(self.headers.get("Content-Length", 0)))
            i = self.server.counter
            self.server.counter += 1
            self._send({"message": {"role": "assistant",
                                    "content": json.dumps(
                                        script[min(i, len(script) - 1)])}})

        def log_message(self, *a):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.counter = 0
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


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
def parts(tmp_path):
    db = Database(tmp_path / "fam.db")
    events = EventBroadcaster()
    engine = SpellEngine(registry, db, events)
    spell_library.bind_archive(db)
    engine.sync_registry_to_db()
    yield db, events, engine
    db.close()


def _familiar(parts, url):
    db, events, engine = parts
    client = OllamaClient(url, "llama3.1")
    return Familiar(engine, db, events, client, load_settings()), db, events


async def test_plain_reply_persists_and_remembers(parts):
    # Two turns: the model just replies each time; memory must accumulate.
    server, url = _make_server([
        {"thought": "greet", "tool": None, "reply": "Hello operator."},
        {"thought": "recall", "tool": None, "reply": "You said hi earlier."},
    ])
    try:
        fam, db, _ = _familiar(parts, url)
        r1 = await fam.chat(None, "hi")
        cid = r1["conversation_id"]
        assert r1["reply"] == "Hello operator."
        r2 = await fam.chat(cid, "what did I say?")
        assert r2["conversation_id"] == cid
        # 4 messages persisted: user, assistant, user, assistant.
        msgs = db.list_messages(cid)
        assert [m["role"] for m in msgs] == \
            ["user", "assistant", "user", "assistant"]
    finally:
        server.shutdown()


async def test_familiar_summons_scryer_then_replies(parts, listener):
    db, events, engine = parts
    db.add_target("127.0.0.1/32", label="lab")
    # The model: call summon_scryer, then (seeing the result) reply.
    server, url = _make_server([
        {"thought": "scan the lab", "tool": {"name": "summon_scryer",
         "args": {"objective": "inventory", "scope": "127.0.0.1/32"}},
         "reply": None},
        {"thought": "report", "tool": None,
         "reply": "Found and recorded the lab host."},
    ])
    try:
        fam, db, events = _familiar(parts, url)
        # Watch the event bus for live progress.
        q = events.subscribe()
        result = await fam.chat(None, "scan my lab and tell me what's there")
        assert "lab host" in result["reply"]
        assert result["trace"] and result["trace"][0]["tool"] == "summon_scryer"
        # The summon really ran: a discovery finding exists.
        assert any(f["kind"] == "discovery" for f in db.list_findings())
        # Progress events were emitted.
        seen = set()
        while not q.empty():
            seen.add(q.get_nowait()["type"])
        assert "familiar.tool" in seen and "familiar.reply" in seen
    finally:
        server.shutdown()


async def test_tool_result_is_fenced_as_untrusted(parts):
    # list_findings then reply; we assert the model received a fenced payload.
    captured = {}

    class SpyHandler(BaseHTTPRequestHandler):
        script = [
            {"thought": "look", "tool": {"name": "list_findings", "args": {}},
             "reply": None},
            {"thought": "done", "tool": None, "reply": "No findings yet."},
        ]

        def _send(self, obj):
            data = json.dumps(obj).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers(); self.wfile.write(data)

        def do_GET(self):
            self._send({"models": [{"name": "llama3.1"}]})

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            i = self.server.counter
            self.server.counter += 1
            if i == 1:  # second call carries the tool result
                captured["messages"] = json.loads(body)["messages"]
            self._send({"message": {"role": "assistant",
                        "content": json.dumps(SpyHandler.script[min(i, 1)])}})

        def log_message(self, *a):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), SpyHandler)
    server.counter = 0
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        fam, _, _ = _familiar(parts, url)
        await fam.chat(None, "any findings?")
        fed = "\n".join(m["content"] for m in captured["messages"])
        assert "<untrusted_network_data>" in fed
    finally:
        server.shutdown()
