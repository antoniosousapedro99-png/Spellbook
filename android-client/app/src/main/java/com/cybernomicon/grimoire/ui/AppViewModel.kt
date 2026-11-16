package com.cybernomicon.grimoire.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybernomicon.grimoire.BuildConfig
import com.cybernomicon.grimoire.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
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
    val telemetry: VeilTelemetryResponse? = null,
    val live: List<LiveEvent> = emptyList(),
    val liveConnected: Boolean = false,
    val lastReport: DaemonReport? = null,
    // Familiar conversation.
    val chat: List<ChatTurn> = emptyList(),
    val conversationId: String? = null,
    val chatBusy: Boolean = false,
)

/** A single rendered chat bubble. `trace` holds any tool calls the Familiar ran. */
data class ChatTurn(val role: String, val text: String,
                    val trace: List<String> = emptyList())

class AppViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: HubApi? = null
    private var feed: LiveFeedClient? = null

    fun setHubUrl(url: String) = _state.update { it.copy(hubUrl = url) }
    fun setToken(t: String) = _state.update { it.copy(token = t) }

    /** Connect to the hub and load the initial Grimoire + Astral + Chronicle. */
    fun connect() = run {
        val s = _state.value
        api = HubClient.create(s.hubUrl, s.token)
        startLiveFeed(s.hubUrl, s.token)
        refreshAll()
    }

    /** Open the live activity WebSocket; events stream into state.live. */
    private fun startLiveFeed(url: String, token: String) {
        feed?.close()
        feed = LiveFeedClient(url, token,
            onEvent = { ev ->
                _state.update { it.copy(live = (listOf(ev) + it.live).take(80)) }
            },
            onState = { up -> _state.update { it.copy(liveConnected = up) } })
        feed!!.connect()
    }

    override fun onCleared() {
        feed?.close()
        super.onCleared()
    }

    fun refreshAll() = withApi { a ->
        _state.update { it.copy(busy = true, error = null) }
        try {
            val spells = a.spells().spells
            val daemons = a.daemons().daemons
            val targets = a.targets().targets
            val devices = a.devices().devices
            val topology = try { a.topology() } catch (_: Exception) { null }
            val findings = a.findings().findings
            // Planner status is best-effort: a slow/absent local model must
            // not block the rest of the Grimoire from loading.
            val planner = try { a.planner() } catch (_: Exception) { null }
            val veil = try { a.veilStatus() } catch (_: Exception) { null }
            val telemetry = try { a.veilTelemetry() } catch (_: Exception) { null }
            _state.update {
                it.copy(connected = true, busy = false, spells = spells,
                    daemons = daemons, targets = targets, devices = devices,
                    topology = topology, findings = findings, planner = planner,
                    veil = veil, telemetry = telemetry)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, connected = false,
                error = e.message ?: "connection failed") }
        }
    }

    fun authorizeTarget(cidr: String, label: String) = withApi { a ->
        _state.update { it.copy(busy = true, error = null) }
        try {
            a.addTarget(AddTargetBody(cidr = cidr, label = label))
            _state.update { it.copy(targets = a.targets().targets, busy = false) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    /** Familiar: issue a natural-language objective to a daemon. */
    fun summon(daemonKey: String, objective: String, scope: String?) = withApi { a ->
        _state.update { it.copy(busy = true, error = null, lastReport = null) }
        try {
            val report = a.summon(daemonKey, SummonBody(objective, scope))
            _state.update {
                it.copy(busy = false, lastReport = report,
                    devices = a.devices().devices,
                    topology = runCatching { a.topology() }.getOrNull()
                        ?: it.topology,
                    findings = a.findings().findings)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    /** Familiar: send a conversational message; the agent may call tools. */
    fun sendChat(message: String) = withApi { a ->
        val text = message.trim()
        if (text.isEmpty()) return@withApi
        _state.update {
            it.copy(chatBusy = true, error = null,
                chat = it.chat + ChatTurn("user", text))
        }
        try {
            val resp = a.chat(ChatBody(text, _state.value.conversationId))
            val trace = resp.trace.map { t ->
                "⚙ ${t.tool}${if (t.summary.isNotBlank()) " — ${t.summary}" else ""}"
            }
            _state.update {
                it.copy(chatBusy = false, conversationId = resp.conversationId,
                    chat = it.chat + ChatTurn("assistant", resp.reply, trace),
                    // The Familiar may have changed the world; refresh views.
                    )
            }
            // Pull any devices/findings/topology/telemetry the Familiar produced.
            _state.update { it.copy(devices = a.devices().devices,
                findings = a.findings().findings,
                topology = runCatching { a.topology() }.getOrNull()
                    ?: it.topology,
                telemetry = runCatching { a.veilTelemetry() }.getOrNull()
                    ?: it.telemetry) }
        } catch (e: Exception) {
            _state.update {
                it.copy(chatBusy = false,
                    chat = it.chat + ChatTurn("assistant",
                        "⚠ ${e.message ?: "request failed"}"))
            }
        }
    }

    // --- VEIL controls ----------------------------------------------------
    fun setIntensity(level: String) = withApi { a ->
        try {
            a.veilIntensity(IntensityBody(level))
            _state.update { it.copy(veil = a.veilStatus()) }
        } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }

    fun emergencyStop() = withApi { a ->
        try { a.veilHalt(); _state.update { it.copy(veil = a.veilStatus()) } }
        catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }

    fun resumeVeil() = withApi { a ->
        try { a.veilResume(); _state.update { it.copy(veil = a.veilStatus()) } }
        catch (e: Exception) { _state.update { it.copy(error = e.message) } }
    }

    private fun withApi(block: suspend (HubApi) -> Unit) {
        val a = api ?: HubClient.create(_state.value.hubUrl, _state.value.token)
            .also { api = it }
        viewModelScope.launch { block(a) }
    }
}
