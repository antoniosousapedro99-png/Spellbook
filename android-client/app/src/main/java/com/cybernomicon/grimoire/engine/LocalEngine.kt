package com.cybernomicon.grimoire.engine

import com.cybernomicon.grimoire.data.Topology

/**
 * The on-device hub: a single facade the UI drives instead of (or alongside)
 * the remote Python hub. Everything here runs inside the app — no Termux, no
 * server. (Phase 2 wires the ViewModel to this; Phase 3 adds the on-device
 * LLM Familiar.)
 */
class LocalEngine {
    private val store = EngineStore()
    val veil = VeilCore(store)
    private val engine = SpellEngine(store, veil)
    private val scryer = Scryer(engine, store)

    // --- Grimoire ---------------------------------------------------------
    fun spells(): List<SpellInfo> = engine.spells
    fun authorizeTarget(cidr: String, label: String): TargetRow =
        store.addTarget(cidr, label)
    fun targets(): List<TargetRow> = store.listTargets()

    // --- Astral Plane -----------------------------------------------------
    fun devices(): List<DeviceRow> = store.listDevices()
    fun topology(): Topology = NativeTopology.build(store.listDevices())

    // --- Chronicle / VEIL -------------------------------------------------
    fun findings(): List<FindingRow> = store.listFindings()
    fun telemetry(): List<TelemetryRow> = store.listTelemetry()
    fun veilStatus(): Map<String, Any> = veil.status()
    fun setIntensity(level: String): Intensity = veil.setIntensity(level)
    fun emergencyStop() = veil.emergencyStop()
    fun resume() = veil.resume()

    // --- actions ----------------------------------------------------------
    /** Cast a spell directly; device-shaped results are persisted (fills the
     *  Astral Plane), mirroring the hub's record_spell_effects. */
    suspend fun cast(spellId: String, params: Map<String, Any?>,
                     onProgress: (String) -> Unit = {}): ExecutionResult {
        val res = engine.execute(spellId, params, onProgress)
        if (res.ok) recordEffects(spellId, res.result)
        return res
    }

    suspend fun summonScryer(objective: String, scope: String?): DaemonRunReport =
        scryer.run(objective, scope)

    private fun recordEffects(spellId: String, result: Map<String, Any?>?) {
        if (result == null) return
        when (spellId) {
            "scry" -> {
                @Suppress("UNCHECKED_CAST")
                val devices = (result["devices"] as? List<Map<String, Any?>>)
                    ?: emptyList()
                for (d in devices) {
                    @Suppress("UNCHECKED_CAST")
                    val ports = (d["open_ports"] as? List<Int>) ?: emptyList()
                    store.upsertDevice(d["address"] as String) {
                        it.hostname = d["hostname"] as? String
                        it.status = (d["status"] as? String) ?: "up"
                        it.openPorts = ports
                    }
                }
            }
            "identify" -> {
                @Suppress("UNCHECKED_CAST")
                val services = (result["services"] as? List<Map<String, Any?>>)
                    ?: emptyList()
                store.upsertDevice(result["address"] as String) {
                    it.services = services.map { s ->
                        (s["port"] as Int) to (s["guess"] as String)
                    }
                }
            }
        }
    }
}
