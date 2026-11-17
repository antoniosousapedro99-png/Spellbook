package com.cybernomicon.grimoire.engine

/**
 * Minimal on-device LLM abstraction so the Familiar loop is independent of the
 * backend engine. [MediaPipeLlm] is the real implementation; a stub is used in
 * tests. Keeping this an interface isolates the (untestable-here) native calls
 * to a single file.
 */
interface LlmEngine {
    val ready: Boolean
    /** Generate a completion for [prompt]. Returns the model's raw text. */
    suspend fun generate(prompt: String): String
    fun close() {}
}
