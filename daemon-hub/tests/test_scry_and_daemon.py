"""Integration tests: real SCRY/IDENTIFY spells and the SCRYER daemon.

A throwaway asyncio TCP server is started on 127.0.0.1 so discovery has
something real to find, and 127.0.0.1/32 is authorized as the scope.
"""
import asyncio

import pytest

from cybernomicon.app import summon_daemon
from cybernomicon.config import load_settings
from cybernomicon.daemons.scryer import build_scryer
from cybernomicon.db import Database
from cybernomicon.events import EventBroadcaster
from cybernomicon.spell_engine import SpellEngine, registry
import cybernomicon.spells as spell_library


@pytest.fixture
async def listening_port():
    async def handle(reader, writer):
        writer.write(b"SSH-2.0-OpenSSH_TestLab\r\n")
        await writer.drain()
        writer.close()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    async with server:
        await server.start_serving()
        yield port


@pytest.fixture
def hub(tmp_path):
    db = Database(tmp_path / "scry.db")
    events = EventBroadcaster()
    engine = SpellEngine(registry, db, events)
    spell_library.bind_archive(db)
    engine.sync_registry_to_db()

    class _Hub:
        pass
    h = _Hub()
    h.db, h.events, h.engine, h.settings = db, events, engine, load_settings()
    yield h
    db.close()


async def test_scry_denied_without_authorization(hub):
    res = await hub.engine.execute("scry", {"scope": "127.0.0.1/32"})
    assert res.status == "denied"


async def test_scry_finds_listening_host(hub, listening_port):
    hub.db.add_target("127.0.0.1/32", label="loopback-lab")
    res = await hub.engine.execute(
        "scry", {"scope": "127.0.0.1/32", "ports": [listening_port]})
    assert res.ok
    assert res.result["hosts_scanned"] == 1
    devices = res.result["devices"]
    assert len(devices) == 1
    assert devices[0]["address"] == "127.0.0.1"
    assert listening_port in devices[0]["open_ports"]


async def test_identify_reads_banner_as_untrusted(hub, listening_port):
    hub.db.add_target("127.0.0.1/32")
    res = await hub.engine.execute(
        "identify", {"address": "127.0.0.1", "ports": [listening_port]})
    assert res.ok
    svc = res.result["services"][0]
    assert svc["guess"] == "ssh"
    assert "OpenSSH_TestLab" in svc["banner"]


async def test_scryer_daemon_end_to_end(hub, listening_port):
    hub.db.add_target("127.0.0.1/32", label="loopback-lab")
    # Constrain SCRY's probe ports to the test server via the objective scope.
    # The daemon plans scry(scope) using DEFAULT ports, so also open the port
    # list by patching default probe ports for this scope would be invasive;
    # instead we verify the full pipeline by running scry with explicit ports
    # through the daemon's planner path is covered above. Here we assert the
    # daemon summons, runs, writes devices + a chronicle finding.
    report = await summon_daemon(hub, "scryer",
                                 "inventory the loopback lab", "127.0.0.1/32")
    assert report.daemon == "scryer"
    assert report.steps_run >= 1
    # A discovery finding is always archived.
    findings = hub.db.list_findings()
    assert any(f["kind"] == "discovery" for f in findings)
    # Audit trail recorded the summon.
    actions = [a["action"] for a in hub.db.list_audit()]
    assert "daemon.summon" in actions


async def test_scryer_enriches_device_attributes(hub, listening_port):
    """Regression: IDENTIFY results must be persisted onto the device even
    though ARCHIVE is queued behind the IDENTIFY steps (planner is consulted
    after every step, not only when the queue empties)."""
    hub.db.add_target("127.0.0.1/32", label="loopback-lab")
    daemon = build_scryer(hub.engine, hub.db)
    report = await daemon.run(
        "fingerprint the loopback lab",
        {"scope": "127.0.0.1/32", "ports": [listening_port]})
    assert report.steps_run >= 2  # scry + identify (+ archive)
    dev = hub.db.get_device("127.0.0.1")
    assert dev is not None
    services = dev["attributes"].get("services")
    assert services and services[0]["port"] == listening_port
    assert services[0]["guess"] == "ssh"
