package com.cybernomicon.grimoire.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.cybernomicon.grimoire.data.*
import com.cybernomicon.grimoire.ui.AppViewModel
import com.cybernomicon.grimoire.ui.ChatTurn
import com.cybernomicon.grimoire.ui.UiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- shared bits ----------------------------------------------------------

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(16.dp))

@Composable
private fun InfoCard(title: String, subtitle: String, body: String = "") {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorBanner(state: UiState) {
    state.error?.let {
        Surface(color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("⚠ $it", Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

// ---- GRIMOIRE: connection + spell/daemon library --------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrimoireScreen(vm: AppViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Grimoire")
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Engine", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.localMode) "on-device" else "remote hub",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.localMode,
                        onCheckedChange = { vm.setLocalMode(it) })
                }
                if (state.localMode) {
                    Text("Runs entirely on this device — no hub, no Termux. " +
                        "The AI Familiar needs the remote hub for now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                } else {
                    OutlinedTextField(
                        value = state.hubUrl, onValueChange = vm::setHubUrl,
                        label = { Text("Hub URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(
                        value = state.token, onValueChange = vm::setToken,
                        label = { Text("API token") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::connect, enabled = !state.busy) {
                        Text(when {
                            state.connected -> "Reconnect"
                            state.localMode -> "Power on"
                            else -> "Link"
                        })
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        ErrorBanner(state)

        if (state.localMode) OnDeviceModelCard(vm, state)

        VeilControlCard(vm, state)

        // Authorized scopes — the security boundary. Nothing is scanned until
        // a scope is authorized here.
        var cidr by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Authorized scopes", style = MaterialTheme.typography.titleMedium)
                Text("Only networks you are permitted to test.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.targets.forEach {
                    Text("• ${it.cidr}${if (it.label.isNotBlank()) "  (${it.label})" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedTextField(cidr, { cidr = it },
                    label = { Text("CIDR, e.g. 192.168.1.0/24") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(label, { label = it },
                    label = { Text("Label (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(
                    onClick = { vm.authorizeTarget(cidr, label); cidr = ""; label = "" },
                    enabled = state.connected && !state.busy && cidr.isNotBlank(),
                    modifier = Modifier.padding(top = 12.dp)) {
                    Text("Authorize")
                }
            }
        }

        Text("Daemons", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        state.daemons.forEach { d ->
            InfoCard("${d.name} — ${d.role}", d.permissions.joinToString(), d.description)
        }

        Row(Modifier.padding(start = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Spells", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            Text("tap to cast", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary)
        }
        state.lastCast?.let {
            Text(it, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        var castTarget by remember { mutableStateOf<SpellSpec?>(null) }
        state.spells.forEach { s ->
            ElevatedCard(
                onClick = { castTarget = s },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${s.name}  v${s.version} ⚡",
                        style = MaterialTheme.typography.titleMedium)
                    Text("perms: ${s.permissions.joinToString().ifBlank { "none" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    if (s.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(s.description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        castTarget?.let { sp ->
            CastSpellDialog(sp,
                onDismiss = { castTarget = null },
                onCast = { params -> vm.castSpell(sp.id, params); castTarget = null })
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- ASTRAL PLANE: interactive network topology ---------------------------

@Composable
fun AstralPlaneScreen(state: UiState) {
    val topo = state.topology
    var selected by remember(topo) { mutableStateOf<TopoNode?>(null) }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("Astral Plane")
        if (topo == null || topo.nodes.isEmpty()) {
            EmptyHint("The plane is dark — no devices discovered yet.\n\n" +
                "Cast Scry (Grimoire ⚡) or summon Scryer on an authorized " +
                "scope. If a scan reports success but finds 0 devices, the " +
                "scope probably doesn't match your network — check your " +
                "phone's IP and scan that /24 (e.g. 192.168.1.0/24).")
        } else {
            // Detail of the tapped node.
            selected?.let { n ->
                InfoCard(
                    n.label,
                    "${n.kind}${n.address?.let { " · $it" } ?: ""} · ${n.status}",
                    buildString {
                        if (n.role.isNotBlank()) append("services: ${n.role}\n")
                        if (n.openPorts.isNotEmpty())
                            append("open ports: ${n.openPorts.joinToString()}")
                    }.ifBlank { "tap nodes to inspect" })
            }
            GraphCanvas(topo, selected?.id) { selected = it }
        }
    }
}

// ---- FAMILIAR: conversational AI interface --------------------------------

@Composable
fun FamiliarScreen(vm: AppViewModel, state: UiState) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the latest message.
    LaunchedEffect(state.chat.size, state.chatBusy) {
        val n = state.chat.size + if (state.chatBusy) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Familiar", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            state.planner?.let { p ->
                val ready = p.planner == "local" && p.available == true
                Text(if (ready) "🜂 ${p.model}" else "🜂 offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.chat.isEmpty()) {
                item {
                    val hint = if (!state.supportsChat)
                        "The AI Familiar is asleep. Load an on-device model " +
                            "(Grimoire → On-device AI), or switch the Engine to " +
                            "remote hub for the Ollama Familiar."
                    else "Ask the Familiar to investigate. e.g. “scan my lab " +
                        "and tell me what services are exposed.” It can summon " +
                        "daemons and read the Astral Plane and Chronicle for you."
                    Text(hint, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
            }
            items(state.chat) { turn -> ChatBubble(turn) }
            if (state.chatBusy) {
                item {
                    Row(Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp),
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("the Familiar is working…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Speak to the Familiar…") },
                    modifier = Modifier.weight(1f), maxLines = 4,
                    enabled = state.connected && !state.chatBusy)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { vm.sendChat(draft); draft = "" },
                    enabled = state.connected && !state.chatBusy && draft.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val mine = turn.role == "user"
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.88f)) {
            Column(Modifier.padding(12.dp)) {
                if (turn.trace.isNotEmpty()) {
                    turn.trace.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- RITUALS: placeholder for Phase VI ------------------------------------

@Composable
fun RitualsScreen() {
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Rituals")
        EmptyHint("Workflow rituals arrive in Phase VI: chain spells and " +
            "daemons with conditions, scheduling and approval checkpoints.")
    }
}

// ---- CHRONICLE: findings history ------------------------------------------

@Composable
fun ChronicleScreen(state: UiState) {
    val tel = state.telemetry
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Chronicle", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text(if (state.liveConnected) "● live" else "○ offline",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.liveConnected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)) {

            // --- VEIL detection telemetry -----------------------------------
            item { SubHeader("VEIL · detection telemetry") }
            item {
                val s = tel?.summary
                val byVis = s?.byVisibility ?: emptyMap()
                ElevatedCard(Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            VisStat("low", byVis["low"] ?: 0, MaterialTheme.colorScheme.primary)
                            VisStat("medium", byVis["medium"] ?: 0, MaterialTheme.colorScheme.tertiary)
                            VisStat("high", byVis["high"] ?: 0, MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${s?.count ?: 0} operations · total noise " +
                            "${s?.totalNoise ?: 0.0} · intensity " +
                            (state.veil?.policy?.intensity ?: "—"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Visibility estimates how noticeable each authorized " +
                            "test is to the target's own defenses.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(tel?.telemetry ?: emptyList()) { r ->
                val color = when (r.visibility) {
                    "high" -> MaterialTheme.colorScheme.error
                    "medium" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("● ", color = color)
                    Text(r.spellId, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text("${r.visibility} · noise ${r.noiseScore}",
                        style = MaterialTheme.typography.labelMedium, color = color)
                }
            }

            // --- live activity feed -----------------------------------------
            item { SubHeader("Live activity") }
            if (state.live.isEmpty()) {
                item {
                    Text("Nothing yet — actions stream here as they happen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            items(state.live) { ev ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                    Text(ev.type, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    if (ev.detail.isNotBlank())
                        Text(ev.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // --- findings ---------------------------------------------------
            item { SubHeader("Findings") }
            if (state.findings.isEmpty()) {
                item {
                    Text("No findings recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            items(state.findings) { f ->
                InfoCard("[${f.severity}] ${f.title}",
                    "${f.kind}${f.daemon?.let { " · $it" } ?: ""}", f.detail)
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun RowScope.VisStat(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Astral Plane graph rendering -----------------------------------------

private class GraphLayout(val pos: Map<String, Offset>, val height: Float)

/** Deterministic radial layout: each connected component is rooted at its
 *  subnet node (or first node), with nodes placed on concentric rings by BFS
 *  depth. Components are stacked vertically. */
private fun layoutGraph(topo: Topology, widthPx: Float): GraphLayout {
    val adj = HashMap<String, MutableList<String>>()
    topo.nodes.forEach { adj[it.id] = mutableListOf() }
    topo.edges.forEach { adj[it.source]?.add(it.target); adj[it.target]?.add(it.source) }

    val pos = HashMap<String, Offset>()
    val visited = HashSet<String>()
    val ring = 150f
    val cx = (widthPx / 2f).coerceAtLeast(160f)
    var y = 120f

    val rootOrder = topo.nodes.filter { it.kind == "subnet" }.map { it.id } +
        topo.nodes.map { it.id }
    for (root in rootOrder) {
        if (root in visited || adj[root] == null) continue
        val depth = HashMap<String, Int>()
        val q = ArrayDeque<String>()
        depth[root] = 0; q.add(root); visited.add(root)
        val comp = mutableListOf(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            for (m in adj[n]!!) if (m !in visited) {
                visited.add(m); depth[m] = depth[n]!! + 1; q.add(m); comp.add(m)
            }
        }
        val byDepth = comp.groupBy { depth[it]!! }
        val maxD = byDepth.keys.maxOrNull() ?: 0
        val centerY = y + maxD * ring
        byDepth.forEach { (d, ids) ->
            if (d == 0) {
                pos[ids[0]] = Offset(cx, centerY)
            } else {
                val r = d * ring
                ids.forEachIndexed { i, id ->
                    val ang = (2.0 * PI * i / ids.size) - PI / 2
                    val x = (cx + (r * cos(ang)).toFloat())
                        .coerceIn(36f, widthPx - 36f)
                    pos[id] = Offset(x, centerY + (r * sin(ang)).toFloat())
                }
            }
        }
        y = centerY + maxD * ring + 130f
    }
    return GraphLayout(pos, y + 40f)
}

@Composable
private fun GraphCanvas(topo: Topology, selectedId: String?,
                        onSelect: (TopoNode) -> Unit) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(scroll)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val laid = remember(topo, widthPx) { layoutGraph(topo, widthPx) }
        val heightDp = with(density) { laid.height.coerceAtLeast(1f).toDp() }
        val nodeById = remember(topo) { topo.nodes.associateBy { it.id } }

        Canvas(Modifier.fillMaxWidth().height(heightDp)
            .pointerInput(laid) {
                detectTapGestures { p ->
                    val hit = laid.pos.minByOrNull { (p - it.value).getDistanceSquared() }
                    if (hit != null && (p - hit.value).getDistance() < 64f)
                        nodeById[hit.key]?.let(onSelect)
                }
            }) {
            topo.edges.forEach { e ->
                val a = laid.pos[e.source]; val b = laid.pos[e.target]
                if (a != null && b != null)
                    drawLine(cs.outline.copy(alpha = 0.5f), a, b, strokeWidth = 2.5f)
            }
            topo.nodes.forEach { n ->
                val c = laid.pos[n.id] ?: return@forEach
                val fill = when (n.kind) {
                    "subnet" -> cs.secondary
                    "gateway" -> cs.tertiary
                    else -> if (n.status == "up") cs.primary else cs.surfaceVariant
                }
                val radius = when (n.kind) {
                    "subnet" -> 30f; "gateway" -> 24f; else -> 20f
                }
                if (n.id == selectedId)
                    drawCircle(cs.onSurface, radius + 6f, c, style = Stroke(width = 3f))
                drawCircle(fill, radius, c)
                val layout = measurer.measure(
                    AnnotatedString(n.label.take(18)),
                    style = TextStyle(fontSize = 11.sp, color = cs.onBackground))
                drawText(layout, topLeft = Offset(
                    c.x - layout.size.width / 2f, c.y + radius + 3f))
            }
        }
    }
}

// ---- VEIL control card (intensity + emergency stop) -----------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VeilControlCard(vm: AppViewModel, state: UiState) {
    val veil = state.veil
    val halted = veil?.terminated == true
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VEIL", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (halted) "⛔ HALTED"
                    else "active · ${veil?.policy?.intensity ?: "—"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (halted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.secondary)
            }
            Text("Operational control: every operation passes through VEIL.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Intensity selector (noise level of authorized testing).
            Row(Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("quiet", "normal", "active").forEach { level ->
                    FilterChip(
                        selected = veil?.policy?.intensity == level,
                        onClick = { vm.setIntensity(level) },
                        enabled = state.connected && !halted,
                        label = { Text(level) })
                }
            }

            // Emergency termination — manifest principle #10.
            Spacer(Modifier.height(12.dp))
            if (halted) {
                Button(onClick = vm::resumeVeil, enabled = state.connected,
                    modifier = Modifier.fillMaxWidth()) { Text("Resume operations") }
            } else {
                Button(onClick = vm::emergencyStop, enabled = state.connected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("⛔  EMERGENCY STOP")
                }
            }
        }
    }
}

// ---- Cast-a-spell dialog (direct, bypasses the Familiar) -------------------

@Composable
private fun CastSpellDialog(
    spell: SpellSpec,
    onDismiss: () -> Unit,
    onCast: (Map<String, JsonElement>) -> Unit,
) {
    val props = (spell.inputSchema["properties"] as? JsonObject)
        ?: JsonObject(emptyMap())
    val required = (spell.inputSchema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
    val fields = remember(spell.id) { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast ${spell.name} ⚡") },
        text = {
            Column {
                if (props.isEmpty()) {
                    Text("This spell takes no parameters — just cast it.")
                }
                props.forEach { (name, def) ->
                    val d = def as? JsonObject
                    val type = (d?.get("type") as? JsonPrimitive)?.contentOrNull ?: "string"
                    val req = name in required
                    OutlinedTextField(
                        value = fields[name] ?: "",
                        onValueChange = { fields[name] = it },
                        singleLine = true,
                        label = { Text(name + (if (req) " *" else "") + "  ($type)") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (props.isNotEmpty()) {
                    Text("Lists are comma-separated, e.g. 22,80,443. " +
                        "* = required.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCast(buildParams(props, fields)) }) {
                Text("Cast")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun buildParams(
    props: JsonObject,
    fields: Map<String, String>,
): Map<String, JsonElement> {
    val out = mutableMapOf<String, JsonElement>()
    for ((name, def) in props) {
        val text = fields[name]?.trim().orEmpty()
        if (text.isEmpty()) continue
        val d = def as? JsonObject
        val type = (d?.get("type") as? JsonPrimitive)?.contentOrNull ?: "string"
        val itemsType = ((d?.get("items") as? JsonObject)?.get("type")
                as? JsonPrimitive)?.contentOrNull
        out[name] = when (type) {
            "integer" -> text.toIntOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(text)
            "number" -> text.toDoubleOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(text)
            "boolean" -> JsonPrimitive(text.lowercase() == "true")
            "array" -> JsonArray(text.split(",").mapNotNull {
                val t = it.trim()
                when {
                    t.isEmpty() -> null
                    itemsType == "integer" -> t.toIntOrNull()?.let { n -> JsonPrimitive(n) }
                    else -> JsonPrimitive(t)
                }
            })
            else -> JsonPrimitive(text)
        }
    }
    return out
}

// ---- On-device AI model loader (Phase 3) ----------------------------------

@Composable
private fun OnDeviceModelCard(vm: AppViewModel, state: UiState) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On-device AI", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        state.modelLoaded -> "🜂 loaded"
                        state.modelLoading -> "loading…"
                        else -> "not loaded"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.modelLoaded) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Loads a Gemma model so the Familiar runs on-device (no hub). " +
                "Download a MediaPipe-format model and push it to the path below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = state.modelPath, onValueChange = vm::setModelPath,
                label = { Text("Model file path") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            state.modelError?.let {
                Text("⚠ $it", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::loadOnDeviceModel,
                    enabled = !state.modelLoading && state.modelPath.isNotBlank()) {
                    Text(if (state.modelLoaded) "Reload model" else "Load model")
                }
                if (state.modelLoading) CircularProgressIndicator(Modifier.size(22.dp))
            }
        }
    }
}
