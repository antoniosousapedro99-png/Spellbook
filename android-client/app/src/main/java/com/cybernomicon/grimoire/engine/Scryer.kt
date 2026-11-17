package com.cybernomicon.grimoire.engine

data class DaemonRunReport(
    val daemon: String,
    val objective: String,
    val stepsRun: Int,
    val findings: List<String>,
    val summary: String,
)

/**
 * SCRYER — the reconnaissance daemon (deterministic planner). Discovers hosts,
 * fingerprints each live one, persists devices, and records a Chronicle
 * summary. The Phase-3 on-device LLM planner slots in behind this same shape.
 */
class Scryer(private val engine: SpellEngine, private val store: EngineStore) {

    suspend fun run(objective: String, scope: String?): DaemonRunReport {
        val findings = mutableListOf<String>()
        var steps = 0
        if (scope.isNullOrBlank()) {
            return DaemonRunReport("scryer", objective, 0,
                listOf("no scope provided"), "Scryer needs an authorized scope.")
        }

        val scryRes = engine.execute("scry", mapOf("scope" to scope))
        steps++
        if (!scryRes.ok) {
            return DaemonRunReport("scryer", objective, steps,
                listOf("scry ${scryRes.status}: ${scryRes.error}"),
                "Scryer could not scan $scope (${scryRes.status}).")
        }
        @Suppress("UNCHECKED_CAST")
        val devices = (scryRes.result?.get("devices") as? List<Map<String, Any?>>)
            ?: emptyList()
        findings.add("Scry found ${devices.size} live host(s) in $scope.")

        for (d in devices) {
            val address = d["address"] as String
            @Suppress("UNCHECKED_CAST")
            val openPorts = (d["open_ports"] as? List<Int>) ?: emptyList()
            store.upsertDevice(address) {
                it.hostname = d["hostname"] as? String
                it.status = (d["status"] as? String) ?: "up"
                it.openPorts = openPorts
            }
            if (openPorts.isNotEmpty()) {
                val idRes = engine.execute("identify",
                    mapOf("address" to address, "ports" to openPorts))
                steps++
                if (idRes.ok) {
                    @Suppress("UNCHECKED_CAST")
                    val services = (idRes.result?.get("services")
                        as? List<Map<String, Any?>>) ?: emptyList()
                    store.upsertDevice(address) {
                        it.services = services.map { s ->
                            (s["port"] as Int) to (s["guess"] as String)
                        }
                    }
                    val svc = services.joinToString(", ") {
                        "${it["port"]}/${it["guess"]}" }
                    if (svc.isNotBlank()) findings.add("Identified $address: $svc")
                }
            }
        }

        engine.execute("archive", mapOf(
            "kind" to "discovery",
            "title" to "Discovered ${devices.size} device(s) in $scope",
            "detail" to devices.joinToString(", ") { it["address"] as String },
            "severity" to "info"))
        steps++

        val fingerprinted = findings.count { it.startsWith("Identified") }
        return DaemonRunReport("scryer", objective, steps, findings,
            "Scryer finished reconnaissance for '$objective': " +
                "$fingerprinted device(s) fingerprinted.")
    }
}
