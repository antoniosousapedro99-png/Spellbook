from cybernomicon.topology import build_topology


def _dev(address, ports=(), services=None, hostname=None):
    return {"address": address, "hostname": hostname, "status": "up",
            "open_ports": list(ports),
            "attributes": {"services": services or []}}


def test_empty():
    t = build_topology([])
    assert t == {"nodes": [], "edges": [], "subnets": []}


def test_groups_by_subnet_and_infers_gateway():
    devs = [
        _dev("192.168.1.1", [80], hostname="router"),      # gateway (.1)
        _dev("192.168.1.10", [22]),
        _dev("192.168.1.77", [445]),
    ]
    t = build_topology(devs)
    kinds = {n["id"]: n["kind"] for n in t["nodes"]}
    assert kinds["subnet:192.168.1.0/24"] == "subnet"
    assert kinds["dev:192.168.1.1"] == "gateway"
    assert kinds["dev:192.168.1.10"] == "device"
    # Gateway links to subnet; other devices hang off the gateway.
    edges = {(e["source"], e["target"]) for e in t["edges"]}
    assert ("subnet:192.168.1.0/24", "dev:192.168.1.1") in edges
    assert ("dev:192.168.1.1", "dev:192.168.1.10") in edges
    assert ("dev:192.168.1.1", "dev:192.168.1.77") in edges


def test_no_gateway_attaches_devices_to_subnet():
    devs = [_dev("10.0.0.50", [22]), _dev("10.0.0.51", [80])]
    t = build_topology(devs)
    edges = {(e["source"], e["target"]) for e in t["edges"]}
    assert ("subnet:10.0.0.0/24", "dev:10.0.0.50") in edges
    assert ("subnet:10.0.0.0/24", "dev:10.0.0.51") in edges
    assert all(e["source"].startswith("subnet:") for e in t["edges"])


def test_role_prefers_service_guess_then_ports():
    devs = [
        _dev("192.168.5.10", [22], services=[{"port": 22, "guess": "ssh"}]),
        _dev("192.168.5.11", [445]),  # no services -> from ports
    ]
    t = build_topology(devs)
    roles = {n["id"]: n["role"] for n in t["nodes"] if n["kind"] == "device"}
    assert roles["dev:192.168.5.10"] == "ssh"
    assert roles["dev:192.168.5.11"] == "smb"


def test_multiple_subnets_are_separate_groups():
    devs = [_dev("192.168.1.10"), _dev("10.0.0.10")]
    t = build_topology(devs)
    assert set(t["subnets"]) == {"192.168.1.0/24", "10.0.0.0/24"}


def test_unparseable_address_bucketed_unknown():
    t = build_topology([_dev("not-an-ip")])
    assert any(n["id"] == "subnet:unknown" for n in t["nodes"])
