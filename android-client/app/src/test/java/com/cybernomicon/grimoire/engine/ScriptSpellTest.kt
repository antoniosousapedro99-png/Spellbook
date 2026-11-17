package com.cybernomicon.grimoire.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ScriptSpellTest {

    private fun api(cidrs: List<String>) = SpellApi(Authorization(cidrs), EngineStore())

    @Test fun returns_a_value() {
        val r = ScriptRunner.run("1 + 2", null, api(emptyList()))
        assertEquals("success", r.status)
        assertEquals("3", r.output)
    }

    @Test fun authorized_connect_and_finding() {
        val server = ServerSocket(0); val port = server.localPort
        thread(isDaemon = true) {
            try { while (!server.isClosed) server.accept().close() } catch (_: Exception) {}
        }
        try {
            val src = """
                if (connect("127.0.0.1", $port, 1000))
                    addFinding("open", "port $port is open", "", "info");
                "scan done";
            """.trimIndent()
            val r = ScriptRunner.run(src, "127.0.0.1", api(listOf("127.0.0.1/32")))
            assertEquals("success", r.status)
            assertEquals(1, r.findingsAdded)
        } finally { server.close() }
    }

    @Test fun unauthorized_host_is_denied() {
        val r = ScriptRunner.run("""connect("10.0.0.1", 80, 300);""", null,
            api(listOf("192.168.0.0/24")))
        assertEquals("denied", r.status)
    }

    @Test fun infinite_loop_times_out() {
        val r = ScriptRunner.run("while(true){}", null, api(emptyList()), timeoutMs = 400)
        assertEquals("error", r.status)
        assertTrue((r.error ?: "").contains("time"))
    }

    @Test fun log_is_captured() {
        val r = ScriptRunner.run("""log("hello"); log("world");""", null, api(emptyList()))
        assertEquals(listOf("hello", "world"), r.logs)
    }
}
