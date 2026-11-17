package com.cybernomicon.grimoire.engine

import kotlinx.coroutines.withTimeoutOrNull

data class ExecutionResult(
    val spellId: String,
    val status: String,            // success | timeout | denied | halted | error
    val result: Map<String, Any?>? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = status == "success"
}

data class SpellInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val permissions: List<String>,
    val timeoutMs: Long,
)

/**
 * Native Spell Engine. Mirrors the Python pipeline: VEIL gate (kill-switch +
 * rate limit + spacing) → run under a timeout → impact assessment + telemetry.
 * Every invocation — direct or via a daemon — goes through here.
 */
class SpellEngine(
    private val store: EngineStore,
    val veil: VeilCore,
) {
    val spells: List<SpellInfo> = listOf(
        SpellInfo("scry", "Scry", "1.0.0",
            "Discover live hosts on an authorized network scope.",
            listOf("network.discover"), 120_000),
        SpellInfo("identify", "Identify", "1.0.0",
            "Fingerprint a device's services from freely-offered banners.",
            listOf("network.discover"), 60_000),
        SpellInfo("archive", "Archive", "1.0.0",
            "Store a finding or summary in the Chronicle.",
            listOf("chronicle.write"), 10_000),
    )

    fun authorization(): Authorization = Authorization(store.authorizedCidrs())

    suspend fun execute(spellId: String, params: Map<String, Any?>,
                        onProgress: (String) -> Unit = {}): ExecutionResult {
        val spell = spells.firstOrNull { it.id == spellId }
            ?: return ExecutionResult(spellId, "error", error = "unknown spell")
        val ctx = ExecutionContext(authorization(), veil.intensity, store, onProgress)
        return try {
            val result = veil.withGate {
                withTimeoutOrNull(spell.timeoutMs) {
                    dispatch(spellId, params, ctx)
                }
            }
            if (result == null) {
                ExecutionResult(spellId, "timeout",
                    error = "timed out after ${spell.timeoutMs}ms")
            } else {
                veil.recordTelemetry(veil.assessImpact(spellId, params, result))
                ExecutionResult(spellId, "success", result)
            }
        } catch (e: EmergencyStop) {
            ExecutionResult(spellId, "halted", error = e.message)
        } catch (e: AuthorizationError) {
            ExecutionResult(spellId, "denied", error = e.message)
        } catch (e: Exception) {
            ExecutionResult(spellId, "error",
                error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun dispatch(spellId: String, params: Map<String, Any?>,
                                ctx: ExecutionContext): Map<String, Any?> =
        when (spellId) {
            "scry" -> scry(params, ctx)
            "identify" -> identify(params, ctx)
            "archive" -> archive(params, ctx)
            else -> throw IllegalArgumentException("unknown spell: $spellId")
        }
}
