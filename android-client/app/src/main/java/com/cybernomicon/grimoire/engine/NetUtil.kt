package com.cybernomicon.grimoire.engine

import java.net.Inet4Address
import java.net.NetworkInterface

/** Helpers for detecting the device's own network, so the operator can scan
 *  the network they're on with one tap (still an explicit authorize-your-own-
 *  network action — not a bypass of the authorization boundary). */
object NetUtil {

    /** The CIDR of the private IPv4 network this device is on, e.g.
     *  "192.168.1.0/24", or null if not on a private network. */
    fun detectLocalCidr(): String? {
        val best = localInterfaceAddress() ?: return null
        val (ip, prefix) = best
        val c = Cidr.parse("$ip/$prefix") ?: return null
        return "${Ip.fromLong(c.network)}/$prefix"
    }

    private fun localInterfaceAddress(): Pair<String, Int>? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        // Cap very wide prefixes (e.g. a /8) to a /24 so a tap
                        // never launches an enormous sweep by accident.
                        val prefix = maxOf(ia.networkPrefixLength.toInt(), 24)
                        return (a.hostAddress ?: continue) to prefix
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }
}
