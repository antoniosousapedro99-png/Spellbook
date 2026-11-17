package com.cybernomicon.grimoire.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class EmergencyStop(message: String) : RuntimeException(message)

enum class Intensity(
    val label: String,
    val maxConcurrency: Int,
    val interOpDelayMs: Long,
    val maxOps: Int,
) {
    QUIET("quiet", 8, 100, 2048),
    NORMAL("normal", 64, 0, 8192),
    ACTIVE("active", 256, 0, 20000);

    companion object {
        fun from(label: String): Intensity =
            entries.firstOrNull { it.label == label } ?: QUIET
    }
}

/**
 * The mandatory control layer. Every spell execution passes through
 * [withGate]. Mirrors the Python VEIL Core: rate limiting, inter-op spacing,
 * an authoritative kill-switch, operational-impact assessment and telemetry.
 * It never conceals activity from the store's own records.
 */
class VeilCore(
    private val store: EngineStore,
    @Volatile var intensity: Intensity = Intensity.QUIET,
) {
    @Volatile var terminated: Boolean = false
        private set
    private var semaphore = Semaphore(intensity.maxConcurrency)
    @Volatile private var lastOpAt = 0L
    var gated: Long = 0L; private set
    var halted: Long = 0L; private set

    fun setIntensity(label: String): Intensity {
        intensity = Intensity.from(label)
        semaphore = Semaphore(intensity.maxConcurrency)
        return intensity
    }

    fun emergencyStop() { terminated = true }
    fun resume() { terminated = false }

    /** Run [block] under the gate: kill-switch + concurrency + spacing. */
    suspend fun <T> withGate(block: suspend () -> T): T {
        if (terminated) { halted++; throw EmergencyStop("VEIL emergency termination active") }
        return semaphore.withPermit {
            if (terminated) { halted++; throw EmergencyStop("VEIL emergency termination active") }
            if (intensity.interOpDelayMs > 0) {
                val now = System.currentTimeMillis()
                val wait = lastOpAt + intensity.interOpDelayMs - now
                if (wait > 0) delay(wait)
                lastOpAt = System.currentTimeMillis()
            }
            gated++
            block()
        }
    }

    /** Estimate network noise + visibility to the TARGET's defenders. */
    fun assessImpact(spellId: String, params: Map<String, Any?>, result: Any?): TelemetryRow {
        var hosts = 1
        var ports = (params["ports"] as? List<*>)?.size ?: 0
        if (result is Map<*, *>) {
            (result["hosts_scanned"] as? Int)?.let { hosts = it }
            if (ports == 0) ports = (result["services"] as? List<*>)?.size ?: 0
        }
        val probes = maxOf(hosts, 1) * maxOf(ports, 1)
        val noise = probes.toDouble()
        val visibility = when { noise < 50 -> "low"; noise < 500 -> "medium"; else -> "high" }
        return TelemetryRow(spellId = spellId, intensity = intensity.label,
            noiseScore = noise, visibility = visibility, hosts = hosts, ports = ports)
    }

    fun recordTelemetry(t: TelemetryRow) = store.addTelemetry(t)

    fun status(): Map<String, Any> = mapOf(
        "intensity" to intensity.label,
        "terminated" to terminated,
        "gated" to gated,
        "halted" to halted,
    )
}
