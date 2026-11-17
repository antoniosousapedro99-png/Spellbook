package com.cybernomicon.grimoire.engine

import com.cybernomicon.grimoire.data.Topology
import com.cybernomicon.grimoire.data.TopoEdge
import com.cybernomicon.grimoire.data.TopoNode

/**
 * Derive an (inferred) network graph from discovered devices — grouping by /24
 * and treating the host at network+1 as the likely gateway. Ported from the
 * Python hub; relationships are inferred, not measured.
 */
object NativeTopology {
    fun build(devices: List<DeviceRow>): Topology {
        val nodes = mutableListOf<TopoNode>()
        val edges = mutableListOf<TopoEdge>()
        val subnets = mutableListOf<String>()

        val groups = devices.groupBy { subnetKey(it.address) }.toSortedMap()
        for ((key, devs) in groups) {
            val subnetId = "subnet:$key"
            nodes.add(TopoNode(id = subnetId, label = key, kind = "subnet"))
            if (key != "unknown") subnets.add(key)

            val gatewayAddr: String? = if (key != "unknown") {
                val base = Cidr.parse(key)!!.network
                val gw = Ip.fromLong(base + 1)
                if (devs.any { it.address == gw }) gw else null
            } else null

            for (d in devs) {
                val isGw = d.address == gatewayAddr
                nodes.add(TopoNode(
                    id = "dev:${d.address}",
                    label = d.hostname ?: d.address,
                    kind = if (isGw) "gateway" else "device",
                    address = d.address,
                    status = d.status,
                    openPorts = d.openPorts,
                    role = d.services.joinToString(",") { it.second }))
            }

            if (gatewayAddr != null) {
                val gid = "dev:$gatewayAddr"
                edges.add(TopoEdge(subnetId, gid))
                devs.filter { it.address != gatewayAddr }
                    .forEach { edges.add(TopoEdge(gid, "dev:${it.address}")) }
            } else {
                devs.forEach { edges.add(TopoEdge(subnetId, "dev:${it.address}")) }
            }
        }
        return Topology(nodes, edges, subnets.sorted())
    }

    private fun subnetKey(address: String): String {
        val v = Ip.toLong(address) ?: return "unknown"
        return "${Ip.fromLong(v and 0xffffff00L)}/24"
    }
}
