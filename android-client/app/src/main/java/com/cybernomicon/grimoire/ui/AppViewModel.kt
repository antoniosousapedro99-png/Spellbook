package com.cybernomicon.grimoire.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cybernomicon.grimoire.BuildConfig
import com.cybernomicon.grimoire.data.*
import com.cybernomicon.grimoire.engine.LocalBackend
import com.cybernomicon.grimoire.engine.MediaPipeLlm
import com.cybernomicon.grimoire.engine.NetUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    val importProgress: Int = -1,   // 0..100 while copying a picked model, else -1
    val lastReport: DaemonReport? = null,
    // Familiar conversation.
    val chat: List<ChatTurn> = emptyList(),
    val conversationId: String? = null,
    val chatBusy: Boolean = false,
    // Forge (custom JS spells).
    val customSpells: List<CustomSpell> = emptyList(),
    val scriptBusy: Boolean = false,
    val scriptOutput: String? = null,
)

/** A single rendered chat bubble. `trace` holds any tool calls the Familiar ran. */
data class ChatTurn(val role: String, val text: String,
                    val trace: List<String> = emptyList())

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("cyberdeck", Context.MODE_PRIVATE)
    private val defaultModelPath =
        (app.getExternalFilesDir(null)?.absolutePath ?: "") + "/model.bin"

    private val _state = MutableStateFlow(UiState(
        localMode = prefs.getBoolean("localMode", true),
        hubUrl = prefs.getString("hubUrl", BuildConfig.DEFAULT_HUB_URL)!!,
        token = prefs.getString("token", "")!!,
        modelPath = prefs.getString("modelPath", defaultModelPath)!!))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var backend: Backend? = null
    private var localBackend: LocalBackend? = null   // kept so data survives relink
    private var feed: LiveFeedClient? = null

    private fun save(key: String, value: Any) = prefs.edit().apply {
        when (value) { is Boolean -> putBoolean(key, value); is String -> putString(key, value) }
    }.apply()

    fun setHubUrl(url: String) { save("hubUrl", url); _state.update { it.copy(hubUrl = url) } }
    fun setToken(t: String) { save("token", t); _state.update { it.copy(token = t) } }
    fun setModelPath(p: String) { save("modelPath", p); _state.update { it.copy(modelPath = p) } }
    fun setLocalMode(on: Boolean) {
        save("localMode", on)
        _state.update { it.copy(localMode = on, connected = false) }
    }

    private fun ensureLocalBackend(): LocalBackend =
        localBackend ?: LocalBackend().also { localBackend = it }

    /** Import a model picked via SAF (a content URI): copy it into app storage,
     *  then load it. Removes the need for `adb push`. */
    fun importModelFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(modelLoading = true, modelError = null, importProgress = 0) }
            try {
                val dest = java.io.File(getApplication<Application>().filesDir, "model.bin")
                withContext(Dispatchers.IO) { copyUriToFile(uri, dest) { pct ->
                    _state.update { it.copy(importProgress = pct) } } }
                save("modelPath", dest.absolutePath)
                _state.update { it.copy(modelPath = dest.absolutePath, importProgress = -1) }
                val llm = withContext(Dispatchers.IO) {
                    MediaPipeLlm.load(getApplication(), dest.absolutePath) }
                ensureLocalBackend().attachLlm(llm)
                _state.update {
                    it.copy(modelLoading = false, modelLoaded = true,
                        supportsChat = backend?.supportsChat ?: true,
                        planner = PlannerStatus(planner = "on-device",
                            model = "imported model", available = true))
                }
            } catch (e: Throwable) {
                _state.update { it.copy(modelLoading = false, importProgress = -1,
                    modelError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun copyUriToFile(uri: android.net.Uri, dest: java.io.File,
                              onProgress: (Int) -> Unit) {
        val resolver = getApplication<Application>().contentResolver
        val total = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        resolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); copied += n
                    if (total > 0) {
                        val pct = (copied * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        } ?: throw java.io.IOException("could not open the selected file")
    }

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

    /** One tap: detect the network this device is on, authorize it, and scan. */
    fun scanMyNetwork() = withBackend { b ->
        val cidr = NetUtil.detectLocalCidr()
        if (cidr == null) {
            _state.update { it.copy(error =
                "Couldn't auto-detect your network — type your CIDR below and tap Scan.") }
            return@withBackend
        }
        authorizeAndScan(b, cidr, "my network")
    }

    /** Manual workaround: authorize + scan a CIDR you type. Typing it is you
     *  asserting you're permitted to test that network. */
    fun scanScope(cidr: String) = withBackend { b ->
        val c = cidr.trim()
        if (c.isEmpty()) return@withBackend
        authorizeAndScan(b, c, "manual")
    }

    private suspend fun authorizeAndScan(b: Backend, cidr: String, label: String) {
        _state.update { it.copy(busy = true, error = null, lastCast = "scanning $cidr…") }
        try {
            if (b.targets().none { it.cidr == cidr }) b.addTarget(cidr, label)
            pushLive("spell.start", "scry $cidr")
            val r = b.cast("scry", mapOf("scope" to JsonPrimitive(cidr)))
            val msg = "⚡ scry $cidr → ${r.status}" + summarizeCast("scry", r.result)
            pushLive("spell.${r.status}", msg)
            _state.update {
                it.copy(busy = false, lastCast = msg, targets = b.targets(),
                    devices = b.devices(),
                    topology = runCatching { b.topology() }.getOrNull() ?: it.topology,
                    findings = b.findings(),
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

    // --- Forge: user-authored JS spells -----------------------------------
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val spellsFile by lazy {
        java.io.File(getApplication<Application>().filesDir, "spells.json")
    }

    init { loadCustomSpells() }

    private fun loadCustomSpells() {
        val loaded = try {
            if (spellsFile.exists())
                json.decodeFromString<List<CustomSpell>>(spellsFile.readText())
            else emptyList()
        } catch (e: Exception) { emptyList() }
        _state.update { it.copy(customSpells = loaded.ifEmpty { seedSpells() }) }
    }

    private fun persistSpells(list: List<CustomSpell>) {
        try { spellsFile.writeText(json.encodeToString(list)) } catch (_: Exception) {}
    }

    fun saveSpell(id: String?, name: String, source: String) {
        val sid = id ?: java.util.UUID.randomUUID().toString()
        val spell = CustomSpell(sid, name.ifBlank { "unnamed" }, source)
        val list = _state.value.customSpells.filter { it.id != sid } + spell
        persistSpells(list)
        _state.update { it.copy(customSpells = list.sortedBy { s -> s.name }) }
    }

    fun deleteSpell(id: String) {
        val list = _state.value.customSpells.filter { it.id != id }
        persistSpells(list)
        _state.update { it.copy(customSpells = list) }
    }

    /** Run a Forge spell on-device (requires on-device engine powered on). */
    fun runSpell(name: String, source: String, target: String) {
        val lb = localBackend
        viewModelScope.launch {
            if (lb == null || !_state.value.localMode || !_state.value.connected) {
                _state.update { it.copy(scriptOutput =
                    "The Forge runs on-device. Set Engine to on-device and Power on first.") }
                return@launch
            }
            _state.update { it.copy(scriptBusy = true, scriptOutput = null) }
            pushLive("spell.start", "forge:$name")
            val r = lb.runScript(name, source, target.ifBlank { null })
            val out = buildString {
                appendLine("status: ${r.status}")
                r.error?.let { appendLine("error: $it") }
                r.logs.forEach { appendLine("· $it") }
                if (r.output.isNotBlank()) appendLine("=> ${r.output}")
                append("findings added: ${r.findingsAdded}")
            }
            pushLive("spell.${r.status}", "forge:$name · ${r.findingsAdded} finding(s)")
            _state.update {
                it.copy(scriptBusy = false, scriptOutput = out,
                    findings = lb.findings(),
                    telemetry = runCatching { lb.veilTelemetry() }.getOrNull() ?: it.telemetry)
            }
        }
    }

    private fun seedSpells(): List<CustomSpell> = listOf(
        CustomSpell(java.util.UUID.randomUUID().toString(), "Port sweep", """
            // Enter your authorized CIDR as the Target, then Run.
            var hosts = expand(scope);
            var ports = [22, 80, 443, 445, 8080, 3389];
            var found = 0;
            for (var i = 0; i < hosts.length; i++) {
              for (var j = 0; j < ports.length; j++) {
                if (connect(hosts[i], ports[j], 500)) {
                  addFinding('open', hosts[i] + ':' + ports[j] + ' open', '', 'info');
                  log(hosts[i] + ':' + ports[j]);
                  found++;
                }
              }
            }
            'found ' + found + ' open ports';
        """.trimIndent()),
        CustomSpell(java.util.UUID.randomUUID().toString(), "Banner grab", """
            // Enter a single authorized host as the Target, then Run.
            var b = banner(host, 22, 1500);
            if (b) { addFinding('banner', host + ':22', b, 'info'); }
            b || 'no banner on port 22';
        """.trimIndent()))
}
