package com.cybernomicon.grimoire.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class EngineTest {

    private var server: ServerSocket? = null

    @After fun tearDown() { server?.close() }

    /** Start a throwaway TCP listener that greets with an SSH banner. */
    private fun startListener(): Int {
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true) {
            try {
                while (!s.isClosed) {
                    val c = s.accept()
                    c.getOutputStream().write("SSH-2.0-OpenSSH_TestLab\r\n".toByteArray())
                    c.getOutputStream().flush()
                    c.close()
                }
            } catch (_: Exception) { }
        }
        return s.localPort
    }

    // --- authorization ----------------------------------------------------

    @Test fun empty_authorization_denies() {
        val a = Authorization(emptyList())
        assertTrue(a.isEmpty)
        assertFalse(a.isAuthorized("10.0.0.1"))
    }

    @Test fun cidr_membership_and_expand() {
        val a = Authorization(listOf("192.168.1.0/24"))
        assertTrue(a.isAuthorized("192.168.1.50"))
        assertFalse(a.isAuthorized("192.168.2.50"))
        assertEquals(254, a.expand("192.168.1.0/24").size)   // .1 .. .254
        assertEquals(listOf("127.0.0.1"), Authorization(listOf("127.0.0.1/32"))
            .expand("127.0.0.1/32"))
    }

    @Test(expected = AuthorizationError::class)
    fun expand_rejects_unauthorized_scope() {
        Authorization(listOf("192.168.0.0/24")).expand("10.0.0.0/24")
    }

    // --- VEIL -------------------------------------------------------------

    @Test fun intensity_presets() {
        assertEquals(8, Intensity.from("quiet").maxConcurrency)
        assertEquals(256, Intensity.from("active").maxConcurrency)
        assertEquals(Intensity.QUIET, Intensity.from("bogus"))
    }

    // --- topology ---------------------------------------------------------

    @Test fun topology_infers_gateway() {
        val devices = listOf(
            DeviceRow("192.168.1.1", hostname = "router", openPorts = listOf(80)),
            DeviceRow("192.168.1.10", openPorts = listOf(22)),
            DeviceRow("192.168.1.77", openPorts = listOf(445)))
        val t = NativeTopology.build(devices)
        val kinds = t.nodes.associate { it.id to it.kind }
        assertEquals("gateway", kinds["dev:192.168.1.1"])
        assertEquals("device", kinds["dev:192.168.1.10"])
        val edges = t.edges.map { it.source to it.target }.toSet()
        assertTrue(("subnet:192.168.1.0/24" to "dev:192.168.1.1") in edges)
        assertTrue(("dev:192.168.1.1" to "dev:192.168.1.10") in edges)
    }

    // --- engine + spells (real sockets) -----------------------------------

    @Test fun scry_denied_without_authorization() = runBlocking {
        val store = EngineStore()
        val engine = SpellEngine(store, VeilCore(store))
        val res = engine.execute("scry", mapOf("scope" to "127.0.0.1/32"))
        assertEquals("denied", res.status)
    }

    @Test fun scry_finds_listening_host_and_persists() = runBlocking {
        val port = startListener()
        val local = LocalEngine()
        local.authorizeTarget("127.0.0.1/32", "lab")
        val res = local.cast("scry",
            mapOf("scope" to "127.0.0.1/32", "ports" to listOf(port)))
        assertTrue(res.ok)
        assertEquals(1, (res.result?.get("devices") as List<*>).size)
        // Persisted → the Astral Plane would show it.
        val dev = local.devices().firstOrNull { it.address == "127.0.0.1" }
        assertNotNull(dev)
        assertTrue(port in dev!!.openPorts)
        // Telemetry recorded.
        assertTrue(local.telemetry().isNotEmpty())
    }

    @Test fun identify_reads_banner() = runBlocking {
        val port = startListener()
        val local = LocalEngine()
        local.authorizeTarget("127.0.0.1/32", "lab")
        val res = local.cast("identify",
            mapOf("address" to "127.0.0.1", "ports" to listOf(port)))
        assertTrue(res.ok)
        @Suppress("UNCHECKED_CAST")
        val services = res.result?.get("services") as List<Map<String, Any?>>
        assertEquals("ssh", services[0]["guess"])
    }

    @Test fun scryer_daemon_end_to_end() = runBlocking {
        val port = startListener()
        val local = LocalEngine()
        local.authorizeTarget("127.0.0.1/32", "lab")
        // Scryer uses default ports; point it by casting scry with the port via
        // a direct scan instead, then verify the daemon path on a found host.
        val report = local.summonScryer("inventory", "127.0.0.1/32")
        assertEquals("scryer", report.daemon)
        assertTrue(report.stepsRun >= 1)
        assertTrue(local.findings().any { it.kind == "discovery" })
    }

    @Test fun emergency_stop_halts_execution() = runBlocking {
        val port = startListener()
        val local = LocalEngine()
        local.authorizeTarget("127.0.0.1/32", "lab")
        local.emergencyStop()
        val res = local.cast("scry",
            mapOf("scope" to "127.0.0.1/32", "ports" to listOf(port)))
        assertEquals("halted", res.status)
        local.resume()
        val res2 = local.cast("scry",
            mapOf("scope" to "127.0.0.1/32", "ports" to listOf(port)))
        assertEquals("success", res2.status)
    }
}
