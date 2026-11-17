package com.cybernomicon.grimoire.engine

/**
 * IPv4 address + CIDR helpers and the authorization boundary, ported from the
 * Python hub. IPv4-only for now (home-network scanning); IPv6 is a later phase.
 */

class AuthorizationError(message: String) : RuntimeException(message)

internal object Ip {
    fun toLong(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun fromLong(v: Long): String =
        "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}." +
            "${(v shr 8) and 0xff}.${v and 0xff}"
}

internal data class Cidr(val base: Long, val prefix: Int) {
    private val mask: Long =
        if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val network: Long = base and mask
    val broadcast: Long = network or (mask.inv() and 0xffffffffL)

    fun contains(ip: Long): Boolean = ip in network..broadcast

    companion object {
        /** Parse "a.b.c.d" (host → /32) or "a.b.c.d/p". */
        fun parse(s: String): Cidr? {
            val t = s.trim()
            val slash = t.indexOf('/')
            val ipPart = if (slash < 0) t else t.substring(0, slash)
            val prefix = if (slash < 0) 32 else
                t.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            val ip = Ip.toLong(ipPart) ?: return null
            return Cidr(ip, prefix)
        }
    }
}

/** The hard security boundary: operations may only target authorized scopes. */
class Authorization(authorizedCidrs: List<String>) {
    private val nets: List<Cidr> = authorizedCidrs.mapNotNull { Cidr.parse(it) }

    val isEmpty: Boolean get() = nets.isEmpty()

    fun isAuthorized(address: String): Boolean {
        val v = Ip.toLong(address.substringBefore('/')) ?: return false
        return nets.any { it.contains(v) }
    }

    fun assertAuthorized(address: String) {
        if (isEmpty) throw AuthorizationError(
            "no authorized targets configured; authorize a scope first")
        if (!isAuthorized(address)) throw AuthorizationError(
            "target '$address' is not within any authorized scope")
    }

    /** Expand an authorized scope into individual host addresses to scan. */
    fun expand(scope: String, maxHosts: Int = 4096): List<String> {
        val c = Cidr.parse(scope)
            ?: throw AuthorizationError("invalid scope: $scope")
        if (isEmpty || nets.none { it.network <= c.network && it.broadcast >= c.broadcast }) {
            throw AuthorizationError("scope $scope is not authorized")
        }
        val hosts: List<Long> = if (c.prefix >= 31) {
            (c.network..c.broadcast).toList()
        } else {
            ((c.network + 1) until c.broadcast).toList()  // drop net + broadcast
        }
        if (hosts.size > maxHosts) throw AuthorizationError(
            "scope $scope expands to ${hosts.size} hosts (> $maxHosts); narrow it")
        return hosts.map { Ip.fromLong(it) }
    }
}
