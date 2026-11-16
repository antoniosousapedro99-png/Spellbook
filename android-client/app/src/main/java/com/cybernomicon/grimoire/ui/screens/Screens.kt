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

@Composable
fun GrimoireScreen(vm: AppViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Grimoire")
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Daemon Hub", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.hubUrl, onValueChange = vm::setHubUrl,
                    label = { Text("Hub URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(
                    value = state.token, onValueChange = vm::setToken,
                    label = { Text("API token") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::connect, enabled = !state.busy) {
                        Text(if (state.connected) "Reconnect" else "Link")
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        ErrorBanner(state)

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

        Text("Spells", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        state.spells.forEach { s ->
            InfoCard("${s.name}  v${s.version}",
                "perms: ${s.permissions.joinToString().ifBlank { "none" }}",
                s.description)
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
            EmptyHint("The plane is dark. Summon Scryer from the Familiar to " +
                "discover devices, and they will appear here as a map.")
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
                    Text("Ask the Familiar to investigate. e.g. “scan my lab " +
                        "and tell me what services are exposed.” It can summon " +
                        "daemons and read the Astral Plane and Chronicle for you.",
                        style = MaterialTheme.typography.bodyMedium,
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
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Chronicle")
        if (state.findings.isEmpty()) {
            EmptyHint("No findings recorded yet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.findings) { f ->
                    InfoCard("[${f.severity}] ${f.title}",
                        "${f.kind}${f.daemon?.let { " · $it" } ?: ""}", f.detail)
                }
            }
        }
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
