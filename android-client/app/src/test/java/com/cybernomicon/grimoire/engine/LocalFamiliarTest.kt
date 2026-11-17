package com.cybernomicon.grimoire.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubLlm(private val responses: MutableList<String>) : LlmEngine {
    override val ready = true
    override suspend fun generate(prompt: String): String =
        if (responses.isNotEmpty()) responses.removeAt(0) else """{"reply":"done"}"""
}

class LocalFamiliarTest {

    @Test fun plain_reply() = runBlocking {
        val llm = StubLlm(mutableListOf(
            """{"thought":"hi","tool":null,"reply":"Hello operator."}"""))
        val turn = LocalFamiliar(llm, LocalEngine()).chat("yo")
        assertEquals("Hello operator.", turn.reply)
        assertTrue(turn.trace.isEmpty())
    }

    @Test fun extracts_json_from_surrounding_prose() = runBlocking {
        val llm = StubLlm(mutableListOf("""Sure thing! {"reply":"ok"} hope that helps"""))
        val turn = LocalFamiliar(llm, LocalEngine()).chat("hi")
        assertEquals("ok", turn.reply)
    }

    @Test fun runs_tool_then_replies() = runBlocking {
        val engine = LocalEngine()
        engine.authorizeTarget("127.0.0.1/32", "lab")
        val llm = StubLlm(mutableListOf(
            """{"tool":{"name":"summon_scryer","args":{"objective":"inv","scope":"127.0.0.1/32"}},"reply":null}""",
            """{"tool":null,"reply":"Recon complete."}"""))
        val turn = LocalFamiliar(llm, engine).chat("scan the lab")
        assertEquals("Recon complete.", turn.reply)
        assertTrue(turn.trace.any { it.first == "summon_scryer" })
        // The tool really ran through the engine: a discovery finding exists.
        assertTrue(engine.findings().any { it.kind == "discovery" })
    }

    @Test fun unknown_tool_is_contained() = runBlocking {
        val llm = StubLlm(mutableListOf(
            """{"tool":{"name":"rm_rf","args":{}},"reply":null}""",
            """{"reply":"nothing to do"}"""))
        val turn = LocalFamiliar(llm, LocalEngine()).chat("x")
        assertEquals("nothing to do", turn.reply)
        assertTrue(turn.trace.any { it.first == "rm_rf" && it.second.contains("no such tool") })
    }
}
