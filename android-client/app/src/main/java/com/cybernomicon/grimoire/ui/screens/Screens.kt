package com.cybernomicon.grimoire.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cybernomicon.grimoire.data.*
import com.cybernomicon.grimoire.ui.AppViewModel
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

// ---- FAMILIAR: conversational objective interface -------------------------

@Composable
fun FamiliarScreen(vm: AppViewModel, state: UiState) {
    var objective by remember { mutableStateOf("Inventory the lab network") }
    var scope by remember { mutableStateOf("") }
    val daemonKey = state.daemons.firstOrNull()?.key ?: "scryer"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Familiar")
        state.planner?.let { p ->
            val line = when {
                p.planner == "local" && p.available == true ->
                    "🜂 Local AI ready — ${p.model}"
                p.planner == "local" ->
                    "🜂 Local AI configured (${p.model}) but not reachable"
                else -> "⚙ Deterministic planner (no LLM)"
            }
            Text(line, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Summon: $daemonKey", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(objective, { objective = it },
                    label = { Text("Objective") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(scope, { scope = it },
                    label = { Text("Authorized scope (CIDR)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(
                    onClick = { vm.summon(daemonKey, objective, scope.ifBlank { null }) },
                    enabled = !state.busy && state.connected,
                    modifier = Modifier.padding(top = 12.dp)) {
                    Text("Summon")
                }
            }
        }
        ErrorBanner(state)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))

        state.lastReport?.let { r ->
            InfoCard("Report · ${r.daemon}", "${r.stepsRun} step(s)", r.summary)
            r.findings.forEach { InfoCard("•", "", it) }
        }
        Spacer(Modifier.height(24.dp))
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
