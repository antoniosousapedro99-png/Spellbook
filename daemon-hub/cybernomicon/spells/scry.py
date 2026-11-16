"""SCRY — discover live hosts on an authorized network scope.

Uses an unprivileged asynchronous TCP-connect sweep plus reverse-DNS lookup.
This needs no root and no external tools (no nmap), which keeps the spell
portable and easy to sandbox. The scope must be an authorized target; the
engine injects the Authorization object and SCRY expands + checks it before
touching the network.
"""
from __future__ import annotations

import asyncio
import socket

from ..spell_engine.base import ExecutionContext
from ..spell_engine import spell

# A small, well-known set of probe ports. Enough to detect most live hosts and
# cheap to attempt. Service-level detail is IDENTIFY's job, not SCRY's.
DEFAULT_PROBE_PORTS = [22, 80, 443, 445, 3389, 8080, 53, 139, 23, 7]

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "scope": {"type": "string",
                   "description": "Authorized CIDR or host, e.g. 192.168.1.0/24"},
        "ports": {"type": "array", "items": {"type": "integer"}},
        "timeout": {"type": "number", "minimum": 0.1, "maximum": 10},
        "concurrency": {"type": "integer", "minimum": 1, "maximum": 512},
    },
    "required": ["scope"],
    "additionalProperties": False,
}

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "scope": {"type": "string"},
        "hosts_scanned": {"type": "integer"},
        "devices": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "address": {"type": "string"},
                    "hostname": {"type": ["string", "null"]},
                    "status": {"type": "string"},
                    "open_ports": {"type": "array", "items": {"type": "integer"}},
                },
                "required": ["address", "status", "open_ports"],
            },
        },
    },
    "required": ["scope", "hosts_scanned", "devices"],
}


async def _probe_port(host: str, port: int, timeout: float) -> bool:
    try:
        fut = asyncio.open_connection(host, port)
        reader, writer = await asyncio.wait_for(fut, timeout=timeout)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        return True
    except (asyncio.TimeoutError, OSError):
        return False


async def _reverse_dns(host: str) -> str | None:
    loop = asyncio.get_running_loop()
    try:
        name, _, _ = await loop.run_in_executor(
            None, socket.gethostbyaddr, host)
        return name
    except (socket.herror, socket.gaierror, OSError):
        return None


async def _scan_host(host: str, ports: list[int], timeout: float) -> dict | None:
    results = await asyncio.gather(
        *(_probe_port(host, p, timeout) for p in ports))
    open_ports = [p for p, ok in zip(ports, results) if ok]
    if not open_ports:
        return None  # No response on any probe port -> treat as down/filtered.
    hostname = await _reverse_dns(host)
    return {"address": host, "hostname": hostname, "status": "up",
            "open_ports": sorted(open_ports)}


@spell(
    id="scry",
    name="Scry",
    version="1.0.0",
    description="Discover live hosts on an authorized network scope.",
    permissions=("network.discover",),
    input_schema=INPUT_SCHEMA,
    output_schema=OUTPUT_SCHEMA,
    timeout_seconds=120.0,
    documentation=(
        "Performs an unprivileged TCP-connect sweep over every host in an "
        "authorized scope, recording which probe ports respond and the "
        "reverse-DNS hostname. Does not perform service interrogation — that "
        "is the IDENTIFY spell. The scope MUST be an authorized target."),
)
async def scry(params: dict, ctx: ExecutionContext) -> dict:
    scope = params["scope"]
    ports = params.get("ports") or DEFAULT_PROBE_PORTS
    timeout = float(params.get("timeout", 1.0))
    concurrency = int(params.get("concurrency", 128))

    # Expand + authorize. Raises AuthorizationError (-> engine returns 'denied')
    # if the scope is outside every configured authorized target.
    hosts = ctx.authorization.expand(scope)
    ctx.progress(f"scrying {len(hosts)} hosts in {scope}", total=len(hosts))

    sem = asyncio.Semaphore(concurrency)
    found: list[dict] = []
    done = 0

    async def worker(host: str) -> None:
        nonlocal done
        async with sem:
            dev = await _scan_host(host, ports, timeout)
        done += 1
        if done % 32 == 0 or done == len(hosts):
            ctx.progress(f"scanned {done}/{len(hosts)}", done=done)
        if dev:
            found.append(dev)
            ctx.progress(f"host up: {dev['address']}", address=dev["address"])

    await asyncio.gather(*(worker(h) for h in hosts))

    return {
        "scope": scope,
        "hosts_scanned": len(hosts),
        "devices": sorted(found, key=lambda d: d["address"]),
    }
