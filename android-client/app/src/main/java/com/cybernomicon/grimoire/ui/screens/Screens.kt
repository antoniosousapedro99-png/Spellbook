package com.cybernomicon.grimoire.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cybernomicon.grimoire.data.*
import com.cybernomicon.grimoire.ui.AppViewModel
import com.cybernomicon.grimoire.ui.ChatTurn
import com.cybernomicon.grimoire.ui.UiState

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

// ---- ASTRAL PLANE: discovered devices -------------------------------------

@Composable
fun AstralPlaneScreen(state: UiState) {
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Astral Plane")
        if (state.devices.isEmpty()) {
            EmptyHint("No devices scried yet. Summon Scryer from the Familiar.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.devices) { d ->
                    InfoCard(
                        d.hostname ?: d.address,
                        "${d.address} · ${d.status}",
                        "open ports: ${d.openPorts.joinToString().ifBlank { "—" }}")
                }
            }
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
