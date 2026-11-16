package com.cybernomicon.grimoire.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs mirroring the Daemon Hub JSON API. Only fields the client needs are
// declared; unknown fields are ignored by the configured Json instance.

@Serializable
data class SpellSpec(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val permissions: List<String> = emptyList(),
    @SerialName("timeout_seconds") val timeoutSeconds: Double = 30.0,
    @SerialName("input_schema")
    val inputSchema: kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class CastResult(
    @SerialName("execution_id") val executionId: String? = null,
    val status: String = "",
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class SpellList(val spells: List<SpellSpec>)

@Serializable
data class DaemonInfo(
    val key: String,
    val name: String,
    val role: String,
    val description: String,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class DaemonList(val daemons: List<DaemonInfo>)

@Serializable
data class ScanTarget(
    val id: String,
    val cidr: String,
    val label: String = "",
    val authorized: Int = 1,
)

@Serializable
data class TargetList(val targets: List<ScanTarget>)

@Serializable
data class AddTargetBody(val cidr: String, val label: String = "", val note: String = "")

@Serializable
data class Device(
    val id: String,
    val address: String,
    val hostname: String? = null,
    val status: String = "up",
    @SerialName("open_ports") val openPorts: List<Int> = emptyList(),
)

@Serializable
data class DeviceList(val devices: List<Device>)

@Serializable
data class TopoNode(
    val id: String,
    val label: String,
    val kind: String,                       // subnet | gateway | device
    val address: String? = null,
    val status: String = "",
    @SerialName("open_ports") val openPorts: List<Int> = emptyList(),
    val role: String = "",
)

@Serializable
data class TopoEdge(val source: String, val target: String)

@Serializable
data class Topology(
    val nodes: List<TopoNode> = emptyList(),
    val edges: List<TopoEdge> = emptyList(),
    val subnets: List<String> = emptyList(),
)

@Serializable
data class Finding(
    val id: String,
    val kind: String,
    val severity: String = "info",
    val title: String,
    val detail: String = "",
    val daemon: String? = null,
)

@Serializable
data class FindingList(val findings: List<Finding>)

@Serializable
data class PlannerStatus(
    val planner: String,
    @SerialName("ollama_url") val ollamaUrl: String? = null,
    val model: String? = null,
    val available: Boolean? = null,
    val models: List<String> = emptyList(),
)

@Serializable
data class VeilPolicy(
    val intensity: String = "",
    @SerialName("max_concurrency") val maxConcurrency: Int = 0,
    @SerialName("inter_op_delay") val interOpDelay: Double = 0.0,
    @SerialName("max_ops_per_run") val maxOpsPerRun: Int = 0,
)

@Serializable
data class VeilStatus(
    val policy: VeilPolicy = VeilPolicy(),
    val terminated: Boolean = false,
    val inflight: Int = 0,
)

@Serializable
data class IntensityBody(val level: String)

@Serializable
data class VeilTelemetryRow(
    val id: String,
    @SerialName("spell_id") val spellId: String,
    val intensity: String = "",
    @SerialName("noise_score") val noiseScore: Double = 0.0,
    val visibility: String = "",
    val hosts: Int = 0,
    val ports: Int = 0,
)

@Serializable
data class VeilTelemetrySummary(
    val count: Int = 0,
    @SerialName("by_visibility") val byVisibility: Map<String, Int> = emptyMap(),
    @SerialName("total_noise") val totalNoise: Double = 0.0,
)

@Serializable
data class VeilTelemetryResponse(
    val telemetry: List<VeilTelemetryRow> = emptyList(),
    val summary: VeilTelemetrySummary = VeilTelemetrySummary(),
)

@Serializable
data class SummonBody(val objective: String, val scope: String? = null)

@Serializable
data class DaemonReport(
    val daemon: String,
    val objective: String,
    @SerialName("steps_run") val stepsRun: Int,
    val findings: List<String> = emptyList(),
    val summary: String,
)

@Serializable
data class CastBody(val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap())

@Serializable
data class ChatBody(val message: String, val conversation_id: String? = null)

@Serializable
data class ChatTrace(val tool: String = "", val summary: String = "")

@Serializable
data class ChatResponse(
    @SerialName("conversation_id") val conversationId: String,
    val reply: String,
    val trace: List<ChatTrace> = emptyList(),
)
