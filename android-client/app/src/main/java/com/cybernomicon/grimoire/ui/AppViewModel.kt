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
    val findings: List<Finding> = emptyList(),
    val planner: PlannerStatus? = null,
    val lastReport: DaemonReport? = null,
)

class AppViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: HubApi? = null

    fun setHubUrl(url: String) = _state.update { it.copy(hubUrl = url) }
    fun setToken(t: String) = _state.update { it.copy(token = t) }

    /** Connect to the hub and load the initial Grimoire + Astral + Chronicle. */
    fun connect() = run {
        val s = _state.value
        api = HubClient.create(s.hubUrl, s.token)
        refreshAll()
    }

    fun refreshAll() = withApi { a ->
        _state.update { it.copy(busy = true, error = null) }
        try {
            val spells = a.spells().spells
            val daemons = a.daemons().daemons
            val targets = a.targets().targets
            val devices = a.devices().devices
            val findings = a.findings().findings
            // Planner status is best-effort: a slow/absent local model must
            // not block the rest of the Grimoire from loading.
            val planner = try { a.planner() } catch (_: Exception) { null }
            _state.update {
                it.copy(connected = true, busy = false, spells = spells,
                    daemons = daemons, targets = targets, devices = devices,
                    findings = findings, planner = planner)
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
                    findings = a.findings().findings)
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    private fun withApi(block: suspend (HubApi) -> Unit) {
        val a = api ?: HubClient.create(_state.value.hubUrl, _state.value.token)
            .also { api = it }
        viewModelScope.launch { block(a) }
    }
}
