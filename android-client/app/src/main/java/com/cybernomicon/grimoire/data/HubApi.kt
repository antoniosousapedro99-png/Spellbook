package com.cybernomicon.grimoire.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Retrofit description of the Daemon Hub REST surface. */
interface HubApi {
    @GET("api/grimoire/spells")
    suspend fun spells(): SpellList

    @GET("api/grimoire/daemons")
    suspend fun daemons(): DaemonList

    @POST("api/spells/{id}/cast")
    suspend fun cast(@Path("id") id: String, @Body body: CastBody): CastResult

    @GET("api/targets")
    suspend fun targets(): TargetList

    @POST("api/targets")
    suspend fun addTarget(@Body body: AddTargetBody): Map<String, ScanTarget>

    @GET("api/astral/devices")
    suspend fun devices(): DeviceList

    @GET("api/astral/topology")
    suspend fun topology(): Topology

    @GET("api/chronicle/findings")
    suspend fun findings(): FindingList

    @GET("api/familiar/planner")
    suspend fun planner(): PlannerStatus

    @POST("api/familiar/chat")
    suspend fun chat(@Body body: ChatBody): ChatResponse

    @GET("api/veil/status")
    suspend fun veilStatus(): VeilStatus

    @POST("api/veil/intensity")
    suspend fun veilIntensity(@Body body: IntensityBody): VeilPolicy

    @POST("api/veil/halt")
    suspend fun veilHalt(): Map<String, Boolean>

    @POST("api/veil/resume")
    suspend fun veilResume(): Map<String, Boolean>

    @GET("api/veil/telemetry")
    suspend fun veilTelemetry(): VeilTelemetryResponse

    @POST("api/daemons/{key}/summon")
    suspend fun summon(@Path("key") key: String, @Body body: SummonBody): DaemonReport
}
