package com.cybernomicon.grimoire.engine

import com.cybernomicon.grimoire.data.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The native engine behind the [Backend] interface — the whole platform running
 * inside the app. No Termux, no hub. The Familiar (chat) is not here yet
 * (Phase 3 adds an on-device LLM); scanning, daemons, VEIL and telemetry are.
 */
class LocalBackend(private val engine: LocalEngine = LocalEngine()) : Backend {
    override val supportsLiveFeed = false
    override val supportsChat: Boolean get() = llm?.ready == true

    private var llm: LlmEngine? = null
    private var familiar: LocalFamiliar? = null

    /** Attach a loaded on-device model, enabling the Familiar chat. */
    fun attachLlm(engineLlm: LlmEngine) {
        llm = engineLlm
        familiar = LocalFamiliar(engineLlm, engine)
    }

    /** Run a user-authored JavaScript spell (Forge). */
    suspend fun runScript(name: String, source: String, host: String?): ScriptResult =
        engine.runScript(name, source, host)

    override suspend fun spells(): List<SpellSpec> =
        engine.spells().map { s ->
            SpellSpec(id = s.id, name = s.name, version = s.version,
                description = s.description, permissions = s.permissions,
                timeoutSeconds = s.timeoutMs / 1000.0,
                inputSchema = schemaFor(s.id))
        }

    override suspend fun daemons(): List<DaemonInfo> = listOf(
        DaemonInfo("scryer", "Scryer", "Reconnaissance",
            "Discovers and inventories devices on authorized networks.",
            listOf("chronicle.write", "network.discover")))

    override suspend fun targets(): List<ScanTarget> =
        engine.targets().map { ScanTarget(it.id, it.cidr, it.label, 1) }

    override suspend fun addTarget(cidr: String, label: String) {
        engine.authorizeTarget(cidr, label)
    }

    override suspend fun devices(): List<Device> =
        engine.devices().map {
            Device(id = it.address, address = it.address, hostname = it.hostname,
                status = it.status, openPorts = it.openPorts)
        }

    override suspend fun topology(): Topology = engine.topology()

    override suspend fun findings(): List<Finding> =
        engine.findings().map {
            Finding(it.id, it.kind, it.severity, it.title, it.detail, it.daemon)
        }

    override suspend fun planner(): PlannerStatus =
        PlannerStatus(planner = "on-device", model = "scanning only",
            available = false)

    override suspend fun veilStatus(): VeilStatus {
        val i = engine.veil.intensity
        return VeilStatus(
            policy = VeilPolicy(i.label, i.maxConcurrency,
                i.interOpDelayMs / 1000.0, i.maxOps),
            terminated = engine.veil.terminated, inflight = 0)
    }

    override suspend fun veilTelemetry(): VeilTelemetryResponse {
        val rows = engine.telemetry().map {
            VeilTelemetryRow(it.id, it.spellId, it.intensity, it.noiseScore,
                it.visibility, it.hosts, it.ports)
        }
        val summary = VeilTelemetrySummary(
            count = rows.size,
            byVisibility = mapOf(
                "low" to rows.count { it.visibility == "low" },
                "medium" to rows.count { it.visibility == "medium" },
                "high" to rows.count { it.visibility == "high" }),
            totalNoise = rows.sumOf { it.noiseScore })
        return VeilTelemetryResponse(rows, summary)
    }

    override suspend fun setIntensity(level: String) { engine.setIntensity(level) }
    override suspend fun halt() { engine.emergencyStop() }
    override suspend fun resume() { engine.resume() }

    override suspend fun cast(spellId: String,
                             params: Map<String, JsonElement>): CastResult {
        val r = engine.cast(spellId, params.mapValues { jsonToAny(it.value) })
        return CastResult(executionId = null, status = r.status,
            result = r.result?.let { anyToJson(it) }, error = r.error)
    }

    override suspend fun summon(key: String, objective: String,
                               scope: String?): DaemonReport {
        val rep: DaemonRunReport = engine.summonScryer(objective, scope)
        return DaemonReport(rep.daemon, rep.objective, rep.stepsRun,
            rep.findings, rep.summary)
    }

    override suspend fun chat(message: String, conversationId: String?): ChatResponse {
        val f = familiar ?: return ChatResponse(conversationId ?: "local",
            "Load an on-device model first (Grimoire → On-device AI), or switch " +
                "to remote-hub mode for the Ollama-backed Familiar.", emptyList())
        val turn = f.chat(message)
        return ChatResponse(conversationId ?: "local", turn.reply,
            turn.trace.map { ChatTrace(it.first, it.second) })
    }

    // --- JSON input schemas so tap-to-cast renders the right fields -------
    private fun schemaFor(id: String): JsonObject = when (id) {
        "scry" -> buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("scope") { put("type", "string") }
                putJsonObject("ports") { put("type", "array") }
                putJsonObject("timeout") { put("type", "number") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("scope"))))
        }
        "identify" -> buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("address") { put("type", "string") }
                putJsonObject("ports") { put("type", "array") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("address"),
                JsonPrimitive("ports"))))
        }
        "archive" -> buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("kind") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("detail") { put("type", "string") }
                putJsonObject("severity") { put("type", "string") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("kind"),
                JsonPrimitive("title"))))
        }
        else -> JsonObject(emptyMap())
    }
}

internal fun jsonToAny(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.intOrNull != null -> e.intOrNull
        e.longOrNull != null -> e.longOrNull
        e.doubleOrNull != null -> e.doubleOrNull
        else -> e.content
    }
    is JsonArray -> e.map { jsonToAny(it) }
    is JsonObject -> e.mapValues { jsonToAny(it.value) }
}

internal fun anyToJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { anyToJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to anyToJson(it.value) })
    else -> JsonPrimitive(v.toString())
}
