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
