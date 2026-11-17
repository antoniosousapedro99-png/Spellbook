package com.cybernomicon.grimoire.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM via MediaPipe LLM Inference. Loads a converted model file
 * (e.g. a Gemma `.bin`/`.task`) from [modelPath] on the device. This is the one
 * place that touches native inference; it cannot be exercised on the build host
 * (no device, no model), so it is kept small and isolated.
 *
 * Model files are large (~1.3 GB for Gemma-2B int4) and are NOT bundled in the
 * APK — the operator downloads one and points the app at it.
 */
class MediaPipeLlm private constructor(
    private val inference: LlmInference,
) : LlmEngine {

    override val ready: Boolean = true

    override suspend fun generate(prompt: String): String =
        withContext(Dispatchers.Default) {
            inference.generateResponse(prompt) ?: ""
        }

    override fun close() {
        try { inference.close() } catch (_: Exception) { }
    }

    companion object {
        /** Load a model; throws if the file is missing/invalid or too big for RAM. */
        fun load(context: Context, modelPath: String, maxTokens: Int = 512): MediaPipeLlm {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()
            return MediaPipeLlm(LlmInference.createFromOptions(context, options))
        }
    }
}
