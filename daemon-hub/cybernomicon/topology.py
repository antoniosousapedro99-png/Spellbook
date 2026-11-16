"""Derive a network topology (nodes + edges) from discovered devices.

Phase I/IV discovery gives us hosts, not a wiring diagram, so relationships are
*inferred*, honestly and conservatively:

  * Devices are grouped by subnet (/24 for IPv4, /64 for IPv6).
  * Within a subnet, the host at network+1 (e.g. .1) is treated as the likely
    gateway; other devices hang off it. With no such host, devices attach
    directly to a synthetic subnet node.

This is a derived view for visualization (the Astral Plane), not a claim of
measured links. A future TRACE spell can replace inference with real hop data.
"""
from __future__ import annotations

import ipaddress
from typing import Any

# Fallback port→role hints when a service guess isn't available.
_PORT_ROLES = {
    22: "ssh", 23: "telnet", 53: "dns", 80: "http", 443: "https",
    445: "smb", 139: "smb", 3389: "rdp", 8080: "http", 8443: "https",
    25: "smtp", 110: "pop3", 143: "imap", 3306: "mysql", 5432: "postgres",
}


def _role(device: dict) -> str:
    services = (device.get("attributes") or {}).get("services") or []
    guesses = [s.get("guess") for s in services
               if s.get("guess") and s.get("guess") != "unknown"]
    if guesses:
        return ",".join(sorted(set(guesses)))
    roles = sorted({_PORT_ROLES[p] for p in device.get("open_ports", [])
                    if p in _PORT_ROLES})
    return ",".join(roles)


def _subnet_key(address: str) -> str:
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return "unknown"
    prefix = 64 if ip.version == 6 else 24
    return str(ipaddress.ip_network(f"{address}/{prefix}", strict=False))


def build_topology(devices: list[dict],
                   authorized_cidrs: list[str] | None = None) -> dict[str, Any]:
    nodes: list[dict] = []
    edges: list[dict] = []

    groups: dict[str, list[dict]] = {}
    for d in devices:
        groups.setdefault(_subnet_key(d["address"]), []).append(d)

    for key in sorted(groups):
        devs = groups[key]
        subnet_id = f"subnet:{key}"
        nodes.append({"id": subnet_id, "label": key, "kind": "subnet",
                      "status": "", "open_ports": [], "role": "",
                      "address": None})

        # Identify the likely gateway (host at network+1) if present.
        gateway_addr: str | None = None
        if key != "unknown":
            net = ipaddress.ip_network(key)
            gw = str(net.network_address + 1)
            if any(d["address"] == gw for d in devs):
                gateway_addr = gw

        for d in devs:
            is_gw = d["address"] == gateway_addr
            nodes.append({
                "id": f"dev:{d['address']}",
                "label": d.get("hostname") or d["address"],
                "kind": "gateway" if is_gw else "device",
                "address": d["address"],
                "status": d.get("status", "up"),
                "open_ports": d.get("open_ports", []),
                "role": _role(d),
            })

        if gateway_addr:
            gid = f"dev:{gateway_addr}"
            edges.append({"source": subnet_id, "target": gid})
            for d in devs:
                if d["address"] != gateway_addr:
                    edges.append({"source": gid, "target": f"dev:{d['address']}"})
        else:
            for d in devs:
                edges.append({"source": subnet_id,
                              "target": f"dev:{d['address']}"})

    return {"nodes": nodes, "edges": edges,
            "subnets": sorted(k for k in groups if k != "unknown")}
