package com.cybernomicon.grimoire.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cybernomicon.grimoire.BuildConfig
import com.cybernomicon.grimoire.data.*
import com.cybernomicon.grimoire.engine.LocalBackend
import com.cybernomicon.grimoire.engine.MediaPipeLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class UiState(
    val localMode: Boolean = true,            // default: run on-device
    val hubUrl: String = BuildConfig.DEFAULT_HUB_URL,
    val token: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val spells: List<SpellSpec> = emptyList(),
    val daemons: List<DaemonInfo> = emptyList(),
    val targets: List<ScanTarget> = emptyList(),
    val devices: List<Device> = emptyList(),
    val topology: Topology? = null,
    val findings: List<Finding> = emptyList(),
    val planner: PlannerStatus? = null,
    val veil: VeilStatus? = null,
    val lastCast: String? = null,
    val telemetry: VeilTelemetryResponse? = null,
    val live: List<LiveEvent> = emptyList(),
    val liveConnected: Boolean = false,
    val supportsChat: Boolean = true,
    // On-device model (Phase 3).
    val modelPath: String = "",
    val modelLoading: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelError: String? = null,
    val lastReport: DaemonReport? = null,
    // Familiar conversation.
    val chat: List<ChatTurn> = emptyList(),
    val conversationId: String? = null,
    val chatBusy: Boolean = false,
)

/** A single rendered chat bubble. `trace` holds any tool calls the Familiar ran. */
data class ChatTurn(val role: String, val text: String,
                    val trace: List<String> = emptyList())

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState(
        modelPath = (app.getExternalFilesDir(null)?.absolutePath ?: "") + "/model.bin"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var backend: Backend? = null
    private var localBackend: LocalBackend? = null   // kept so data survives relink
    private var feed: LiveFeedClient? = null

    fun setHubUrl(url: String) = _state.update { it.copy(hubUrl = url) }
    fun setToken(t: String) = _state.update { it.copy(token = t) }
    fun setModelPath(p: String) = _state.update { it.copy(modelPath = p) }
    fun setLocalMode(on: Boolean) =
        _state.update { it.copy(localMode = on, connected = false) }

    private fun ensureLocalBackend(): LocalBackend =
        localBackend ?: LocalBackend().also { localBackend = it }

    /** Load an on-device LLM from a file path, enabling the native Familiar. */
    fun loadOnDeviceModel() {
        val path = _state.value.modelPath.trim()
        if (path.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(modelLoading = true, modelError = null) }
            try {
                val llm = withContext(Dispatchers.IO) {
                    MediaPipeLlm.load(getApplication(), path)
                }
                ensureLocalBackend().attachLlm(llm)
                val name = path.substringAfterLast('/')
                _state.update {
                    it.copy(modelLoading = false, modelLoaded = true,
                        supportsChat = backend?.supportsChat ?: true,
                        planner = PlannerStatus(planner = "on-device", model = name,
                            available = true))
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(modelLoading = false, modelLoaded = false,
                        modelError = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Connect to the chosen backend (on-device engine or remote hub). */
    fun connect() = run {
        val s = _state.value
        feed?.close(); feed = null
        if (s.localMode) {
            val lb = ensureLocalBackend()
            backend = lb
            _state.update { it.copy(liveConnected = false, supportsChat = lb.supportsChat) }
        } else {
            backend = RemoteBackend(HubClient.create(s.hubUrl, s.token))
            startLiveFeed(s.hubUrl, s.token)
            _state.update { it.copy(supportsChat = true) }
        }
        refreshAll()
    }

    private fun startLiveFeed(url: String, token: String) {
        feed = LiveFeedClient(url, token,
            onEvent = { ev -> _state.update { it.copy(live = (listOf(ev) + it.live).take(80)) } },
            onState = { up -> _state.update { it.copy(liveConnected = up) } })
        feed!!.connect()
    }

    /** Synthesize a live-feed entry for the on-device engine (no WebSocket). */
    private fun pushLive(type: String, detail: String) {
        if (backend?.supportsLiveFeed == true) return
        val ev = LiveEvent(type, detail, System.currentTimeMillis() / 1000.0)
        _state.update { it.copy(live = (listOf(ev) + it.live).take(80)) }
    }

    override fun onCleared() { feed?.close(); super.onCleared() }

    fun refreshAll() = withBackend { b ->
        _state.update { it.copy(busy = true, error = null) }
        try {
            val spells = b.spells()
            val daemons = b.daemons()
            val targets = b.targets()
            val devices = b.devices()
            val topology = runCatching { b.topology() }.getOrNull()
            val findings = b.findings()
            val planner = runCatching { b.planner() }.getOrNull()
            val veil = runCatching { b.veilStatus() }.getOrNull()
            val telemetry = runCatching { b.veilTelemetry() }.getOrNull()
            _state.update {
                it.copy(connected = true, busy = false, spells = spells,
                    daemons = daemons, targets = targets, devices = devices,
                    topology = topology, findings = findings, planner = planner,
                    veil = veil, telemetry = telemetry, supportsChat = b.supportsChat)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, connected = false,
                error = e.message ?: "connection failed") }
        }
    }

    fun authorizeTarget(cidr: String, label: String) = withBackend { b ->
        _state.update { it.copy(busy = true, error = null) }
        try {
            b.addTarget(cidr, label)
            _state.update { it.copy(targets = b.targets(), busy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    fun summon(daemonKey: String, objective: String, scope: String?) = withBackend { b ->
        _state.update { it.copy(busy = true, error = null, lastReport = null) }
        try {
            pushLive("daemon.summon", "$daemonKey · $objective")
            val report = b.summon(daemonKey, objective, scope)
            pushLive("daemon.report", report.summary)
            _state.update {
                it.copy(busy = false, lastReport = report, devices = b.devices(),
                    topology = runCatching { b.topology() }.getOrNull() ?: it.topology,
                    findings = b.findings(),
                    telemetry = runCatching { b.veilTelemetry() }.getOrNull() ?: it.telemetry)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    fun sendChat(message: String) = withBackend { b ->
        val text = message.trim()
        if (text.isEmpty()) return@withBackend
        _state.update { it.copy(chatBusy = true, error = null,
            chat = it.chat + ChatTurn("user", text)) }
        try {
            val resp = b.chat(text, _state.value.conversationId)
            val trace = resp.trace.map { t ->
                "⚙ ${t.tool}${if (t.summary.isNotBlank()) " — ${t.summary}" else ""}" }
            _state.update { it.copy(chatBusy = false, conversationId = resp.conversationId,
                chat = it.chat + ChatTurn("assistant", resp.reply, trace)) }
            _state.update { it.copy(devices = b.devices(), findings = b.findings(),
                topology = runCatching { b.topology() }.getOrNull() ?: it.topology,
                telemetry = runCatching { b.veilTelemetry() }.getOrNull() ?: it.telemetry) }
        } catch (e: Exception) {
            _state.update { it.copy(chatBusy = false,
                chat = it.chat + ChatTurn("assistant", "⚠ ${e.message ?: "request failed"}")) }
        }
    }

    fun castSpell(spellId: String, params: Map<String, JsonElement>) = withBackend { b ->
        _state.update { it.copy(busy = true, error = null, lastCast = null) }
        try {
            pushLive("spell.start", spellId)
            val r = b.cast(spellId, params)
            val msg = "⚡ $spellId → ${r.status}" + summarizeCast(spellId, r.result) +
                (r.error?.let { e -> ": $e" } ?: "")
            pushLive("spell.${r.status}", msg)
            _state.update {
                it.copy(busy = false, lastCast = msg, devices = b.devices(),
                    findings = b.findings(),
                    topology = runCatching { b.topology() }.getOrNull() ?: it.topology,
                    telemetry = runCatching { b.veilTelemetry() }.getOrNull() ?: it.telemetry)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    private fun summarizeCast(spellId: String, result: JsonElement?): String {
        val obj = result as? JsonObject ?: return ""
        fun arrSize(k: String) = (obj[k] as? JsonArray)?.size ?: 0
        fun intOf(k: String) = (obj[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return when (spellId) {
            "scry" -> " · scanned ${intOf("hosts_scanned")}, found ${arrSize("devices")} device(s)"
            "identify" -> " · ${arrSize("services")} service(s)"
            else -> ""
        }
    }

    // --- VEIL controls ----------------------------------------------------
    fun setIntensity(level: String) = withBackend { b ->
        try { b.setIntensity(level); _state.update { it.copy(veil = b.veilStatus()) } }
        catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }
    fun emergencyStop() = withBackend { b ->
        try { b.halt(); pushLive("veil.halt", "emergency termination")
            _state.update { it.copy(veil = b.veilStatus()) } }
        catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }
    fun resumeVeil() = withBackend { b ->
        try { b.resume(); _state.update { it.copy(veil = b.veilStatus()) } }
        catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }

    private fun withBackend(block: suspend (Backend) -> Unit) {
        val b = backend ?: return
        viewModelScope.launch { block(b) }
    }
}
