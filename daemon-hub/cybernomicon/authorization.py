"""Target authorization — the hard security boundary of the platform.

Every network operation must resolve its target(s) to IP addresses and assert
that each one falls inside an explicitly authorized scope. There is no implicit
authorization and no "scan everything" mode. If no authorized targets are
configured, every network spell is denied.

This is enforced centrally (not per-spell) so a new spell cannot accidentally
bypass it: the Spell Engine injects an `Authorization` object into every
execution context, and network spells call `assert_authorized()` before
touching the network.
"""
from __future__ import annotations

import ipaddress
from typing import Iterable


class AuthorizationError(PermissionError):
    """Raised when an operation targets an address outside authorized scope."""


class Authorization:
    """Resolves and enforces the authorized-target allowlist (CIDR ranges)."""

    def __init__(self, authorized_cidrs: Iterable[str]):
        self._networks: list[ipaddress._BaseNetwork] = []
        for cidr in authorized_cidrs:
            try:
                self._networks.append(ipaddress.ip_network(cidr, strict=False))
            except ValueError:
                # A malformed stored scope is ignored rather than silently
                # widening authorization. It simply authorizes nothing.
                continue

    @property
    def is_empty(self) -> bool:
        return not self._networks

    def expand(self, scope: str, max_hosts: int = 4096) -> list[str]:
        """Expand an authorized CIDR/host into individual addresses to scan.

        The scope itself must be authorized. We cap expansion so a stray /8
        cannot launch an unbounded operation.
        """
        self.assert_authorized(scope)
        net = ipaddress.ip_network(scope, strict=False)
        hosts = list(net.hosts()) if net.num_addresses > 2 else [net.network_address]
        if len(hosts) > max_hosts:
            raise AuthorizationError(
                f"scope {scope} expands to {len(hosts)} hosts "
                f"(> limit {max_hosts}); narrow the target")
        return [str(h) for h in hosts]

    def is_authorized(self, address: str) -> bool:
        try:
            target = ipaddress.ip_network(address, strict=False)
        except ValueError:
            return False
        return any(self._contains(net, target) for net in self._networks)

    def assert_authorized(self, address: str) -> None:
        if self.is_empty:
            raise AuthorizationError(
                "no authorized targets configured; add an authorized scope "
                "before running network operations")
        if not self.is_authorized(address):
            raise AuthorizationError(
                f"target {address!r} is not within any authorized scope")

    @staticmethod
    def _contains(outer: ipaddress._BaseNetwork,
                  inner: ipaddress._BaseNetwork) -> bool:
        if outer.version != inner.version:
            return False
        return (inner.network_address >= outer.network_address
                and inner.broadcast_address <= outer.broadcast_address)
