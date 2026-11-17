package com.cybernomicon.grimoire.data

import kotlinx.serialization.json.JsonElement

/**
 * Abstraction the ViewModel talks to, so the UI is identical whether it's
 * driving the remote Python hub or the on-device native engine.
 */
interface Backend {
    val supportsLiveFeed: Boolean
    val supportsChat: Boolean

    suspend fun spells(): List<SpellSpec>
    suspend fun daemons(): List<DaemonInfo>
    suspend fun targets(): List<ScanTarget>
    suspend fun addTarget(cidr: String, label: String)
    suspend fun devices(): List<Device>
    suspend fun topology(): Topology?
    suspend fun findings(): List<Finding>
    suspend fun planner(): PlannerStatus?
    suspend fun veilStatus(): VeilStatus?
    suspend fun veilTelemetry(): VeilTelemetryResponse?
    suspend fun setIntensity(level: String)
    suspend fun halt()
    suspend fun resume()
    suspend fun cast(spellId: String, params: Map<String, JsonElement>): CastResult
    suspend fun summon(key: String, objective: String, scope: String?): DaemonReport
    suspend fun chat(message: String, conversationId: String?): ChatResponse
}

/** Talks to the remote Python Daemon Hub over HTTP. */
class RemoteBackend(private val api: HubApi) : Backend {
    override val supportsLiveFeed = true
    override val supportsChat = true

    override suspend fun spells() = api.spells().spells
    override suspend fun daemons() = api.daemons().daemons
    override suspend fun targets() = api.targets().targets
    override suspend fun addTarget(cidr: String, label: String) {
        api.addTarget(AddTargetBody(cidr = cidr, label = label))
    }
    override suspend fun devices() = api.devices().devices
    override suspend fun topology() = api.topology()
    override suspend fun findings() = api.findings().findings
    override suspend fun planner() = api.planner()
    override suspend fun veilStatus() = api.veilStatus()
    override suspend fun veilTelemetry() = api.veilTelemetry()
    override suspend fun setIntensity(level: String) { api.veilIntensity(IntensityBody(level)) }
    override suspend fun halt() { api.veilHalt() }
    override suspend fun resume() { api.veilResume() }
    override suspend fun cast(spellId: String, params: Map<String, JsonElement>) =
        api.cast(spellId, CastBody(params))
    override suspend fun summon(key: String, objective: String, scope: String?) =
        api.summon(key, SummonBody(objective, scope))
    override suspend fun chat(message: String, conversationId: String?) =
        api.chat(ChatBody(message, conversationId))
}
