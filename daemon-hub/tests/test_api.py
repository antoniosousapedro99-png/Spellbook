"""HTTP-level tests against the FastAPI app via TestClient."""
import pytest
from fastapi.testclient import TestClient

from cybernomicon.main import create_app

TOKEN = "test-token-123"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def client():
    app = create_app()
    with TestClient(app) as c:
        yield c


def test_health_is_open(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_api_requires_auth(client):
    assert client.get("/api/grimoire/spells").status_code == 401
    assert client.get("/api/grimoire/spells",
                      headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_spell_discovery(client):
    r = client.get("/api/grimoire/spells", headers=AUTH)
    assert r.status_code == 200
    ids = {s["id"] for s in r.json()["spells"]}
    assert {"scry", "identify", "archive"} <= ids


def test_daemon_catalogue(client):
    r = client.get("/api/grimoire/daemons", headers=AUTH)
    assert any(d["key"] == "scryer" for d in r.json()["daemons"])


def test_scry_denied_until_target_authorized(client):
    r = client.post("/api/spells/scry/cast", headers=AUTH,
                    json={"params": {"scope": "127.0.0.1/32"}})
    assert r.status_code == 200
    assert r.json()["status"] == "denied"


def test_authorize_target_then_summon(client):
    # Authorize loopback.
    r = client.post("/api/targets", headers=AUTH,
                    json={"cidr": "127.0.0.1/32", "label": "lab"})
    assert r.status_code == 200
    # Summon scryer.
    r = client.post("/api/daemons/scryer/summon", headers=AUTH,
                    json={"objective": "inventory lab", "scope": "127.0.0.1/32"})
    assert r.status_code == 200
    body = r.json()
    assert body["daemon"] == "scryer"
    assert body["steps_run"] >= 1
    # Chronicle has a discovery finding.
    f = client.get("/api/chronicle/findings", headers=AUTH).json()["findings"]
    assert any(x["kind"] == "discovery" for x in f)


def test_astral_topology_endpoint(client):
    r = client.get("/api/astral/topology", headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"nodes", "edges", "subnets"}


def test_planner_status_defaults_to_deterministic(client):
    r = client.get("/api/familiar/planner", headers=AUTH)
    assert r.status_code == 200
    assert r.json()["planner"] == "deterministic"


def test_veil_status_and_intensity(client):
    r = client.get("/api/veil/status", headers=AUTH)
    assert r.status_code == 200
    assert r.json()["policy"]["intensity"] == "quiet"  # default
    r = client.post("/api/veil/intensity", headers=AUTH, json={"level": "normal"})
    assert r.status_code == 200 and r.json()["intensity"] == "normal"
    r = client.post("/api/veil/intensity", headers=AUTH, json={"level": "bogus"})
    assert r.status_code == 400


def test_veil_halt_blocks_casts_until_resume(client):
    client.post("/api/targets", headers=AUTH, json={"cidr": "127.0.0.1/32"})
    client.post("/api/veil/halt", headers=AUTH)
    r = client.post("/api/spells/archive/cast", headers=AUTH, json={"params": {
        "kind": "summary", "title": "should be blocked"}})
    assert r.json()["status"] == "halted"
    client.post("/api/veil/resume", headers=AUTH)
    r = client.post("/api/spells/archive/cast", headers=AUTH, json={"params": {
        "kind": "summary", "title": "now allowed"}})
    assert r.json()["status"] == "success"


def test_unknown_spell_404(client):
    r = client.post("/api/spells/nope/cast", headers=AUTH, json={"params": {}})
    assert r.status_code == 404


def test_archive_spell_writes_chronicle(client):
    r = client.post("/api/spells/archive/cast", headers=AUTH, json={"params": {
        "kind": "summary", "title": "unit test note", "severity": "info"}})
    assert r.json()["status"] == "success"
    f = client.get("/api/chronicle/audit", headers=AUTH).json()["audit"]
    assert any(a["action"] == "spell.start" for a in f)
