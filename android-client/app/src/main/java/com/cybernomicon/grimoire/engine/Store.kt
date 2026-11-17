package com.cybernomicon.grimoire.engine

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Engine-side domain models (independent of the network DTOs).

data class DeviceRow(
    val address: String,
    var hostname: String? = null,
    var status: String = "up",
    var openPorts: List<Int> = emptyList(),
    var services: List<Pair<Int, String>> = emptyList(),
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
)

data class FindingRow(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val severity: String,
    val title: String,
    val detail: String,
    val daemon: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class TargetRow(
    val id: String = UUID.randomUUID().toString(),
    val cidr: String,
    val label: String = "",
)

data class TelemetryRow(
    val id: String = UUID.randomUUID().toString(),
    val spellId: String,
    val intensity: String,
    val noiseScore: Double,
    val visibility: String,
    val hosts: Int,
    val ports: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * In-memory store for Phase 1. Phase 2 swaps this for a Room-backed
 * implementation behind the same surface; nothing else changes.
 */
class EngineStore {
    private val targets = ConcurrentHashMap<String, TargetRow>()
    private val devices = ConcurrentHashMap<String, DeviceRow>()
    private val findings = java.util.Collections.synchronizedList(mutableListOf<FindingRow>())
    private val telemetry = java.util.Collections.synchronizedList(mutableListOf<TelemetryRow>())

    // targets / authorization
    fun addTarget(cidr: String, label: String): TargetRow {
        val row = TargetRow(cidr = cidr, label = label)
        targets[row.id] = row
        return row
    }
    fun listTargets(): List<TargetRow> = targets.values.sortedBy { it.cidr }
    fun removeTarget(id: String): Boolean = targets.remove(id) != null
    fun authorizedCidrs(): List<String> = targets.values.map { it.cidr }

    // devices (Astral Plane)
    fun upsertDevice(address: String, update: (DeviceRow) -> Unit): DeviceRow {
        val row = devices.getOrPut(address) { DeviceRow(address = address) }
        update(row)
        row.lastSeen = System.currentTimeMillis()
        return row
    }
    fun getDevice(address: String): DeviceRow? = devices[address]
    fun listDevices(): List<DeviceRow> = devices.values.sortedBy { it.address }

    // findings (Chronicle)
    fun addFinding(f: FindingRow): FindingRow { findings.add(0, f); return f }
    fun listFindings(limit: Int = 100): List<FindingRow> = findings.take(limit)

    // telemetry (VEIL)
    fun addTelemetry(t: TelemetryRow) { telemetry.add(0, t) }
    fun listTelemetry(limit: Int = 100): List<TelemetryRow> = telemetry.take(limit)
}
