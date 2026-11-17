package com.cybernomicon.grimoire.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/** Context handed to each spell: scope authorization + traffic intensity + store. */
class ExecutionContext(
    val authorization: Authorization,
    val intensity: Intensity,
    val store: EngineStore,
    val onProgress: (String) -> Unit = {},
)

val DEFAULT_PROBE_PORTS = listOf(22, 80, 443, 445, 3389, 8080, 53, 139, 23, 7)

private val HTTP_PORTS = setOf(80, 443, 8080, 8000, 8443)

private suspend fun probePort(host: String, port: Int, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }
    }

private suspend fun reverseDns(host: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val name = InetAddress.getByName(host).canonicalHostName
            if (name != host) name else null
        } catch (e: Exception) {
            null
        }
    }

/** SCRY — discover live hosts on an authorized scope (unprivileged TCP sweep). */
suspend fun scry(params: Map<String, Any?>, ctx: ExecutionContext): Map<String, Any?> {
    val scope = params["scope"] as String
    @Suppress("UNCHECKED_CAST")
    val ports = (params["ports"] as? List<Int>)?.ifEmpty { DEFAULT_PROBE_PORTS }
        ?: DEFAULT_PROBE_PORTS
    val timeout = (params["timeout"] as? Number)?.toInt() ?: 1000

    val hosts = ctx.authorization.expand(scope)   // authorizes + expands
    ctx.onProgress("scrying ${hosts.size} hosts in $scope (${ctx.intensity.label})")

    val sem = Semaphore(ctx.intensity.maxConcurrency)
    val found = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
    coroutineScope {
        hosts.forEach { host ->
            launch {
                sem.withPermit {
                    if (ctx.intensity.interOpDelayMs > 0) delay(ctx.intensity.interOpDelayMs)
                    val open = ports.filter { probePort(host, it, timeout) }
                    if (open.isNotEmpty()) {
                        found.add(mapOf(
                            "address" to host,
                            "hostname" to reverseDns(host),
                            "status" to "up",
                            "open_ports" to open.sorted()))
                    }
                }
            }
        }
    }
    return mapOf(
        "scope" to scope,
        "hosts_scanned" to hosts.size,
        "devices" to found.sortedBy { it["address"] as String })
}

private val SIGNATURES = listOf(
    "SSH-" to "ssh", "HTTP/" to "http", "220 " to "smtp/ftp",
    "RFB " to "vnc", "* OK" to "imap")

private fun classify(port: Int, banner: String?): String {
    if (banner != null) SIGNATURES.firstOrNull { banner.contains(it.first) }
        ?.let { return it.second }
    return mapOf(22 to "ssh", 80 to "http", 443 to "https", 445 to "smb",
        53 to "dns", 3389 to "rdp", 23 to "telnet").getOrDefault(port, "unknown")
}

private suspend fun grabBanner(host: String, port: Int, timeoutMs: Int): String? =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                if (port in HTTP_PORTS) {
                    sock.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
                    sock.getOutputStream().flush()
                }
                val buf = ByteArray(256)
                val n = sock.getInputStream().read(buf)
                if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1).trim()
                    .ifEmpty { null } else null
            }
        } catch (e: Exception) {
            null
        }
    }

/** IDENTIFY — fingerprint a device's services from freely-offered banners. */
suspend fun identify(params: Map<String, Any?>, ctx: ExecutionContext): Map<String, Any?> {
    val address = params["address"] as String
    @Suppress("UNCHECKED_CAST")
    val ports = params["ports"] as List<Int>
    val timeout = (params["timeout"] as? Number)?.toInt() ?: 2000
    ctx.authorization.assertAuthorized(address)
    ctx.onProgress("identifying $address")
    val services = ports.map { port ->
        val banner = grabBanner(address, port, timeout)
        mapOf("port" to port, "guess" to classify(port, banner), "banner" to banner)
    }
    return mapOf("address" to address, "services" to services)
}

/** ARCHIVE — store a finding in the Chronicle. */
suspend fun archive(params: Map<String, Any?>, ctx: ExecutionContext): Map<String, Any?> {
    val f = ctx.store.addFinding(FindingRow(
        kind = params["kind"] as String,
        severity = (params["severity"] as? String) ?: "info",
        title = params["title"] as String,
        detail = (params["detail"] as? String) ?: ""))
    ctx.onProgress("archived: ${f.title}")
    return mapOf("finding_id" to f.id)
}
