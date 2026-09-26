package com.geno.veyra.settings

import androidx.annotation.StringRes
import com.geno.veyra.R

/**
 * Curated list of Gemini Live API models offered in the model picker.
 *
 * Only models that support the Live API with synchronous function calling
 * and the `googleSearch` grounding tool belong here. Deliberately excluded:
 *
 * - `gemini-3.8-live-extended-thinking` — its function calling is
 *   async-only; Veyra's synchronous tool dispatch would hard-error.
 * - `gemini-3.5-transcribe-live` / `gemini-3.5-live-translate-preview` —
 *   transcription/translation-only, not conversational.
 * - Shut down: `gemini-2.0-flash-live-001`, `gemini-live-2.5-flash-preview`.
 * - Deprecated: `gemini-2.5-flash-native-audio-preview-12-2025`,
 *   `gemini-omni-flash`.
 */
data class LiveModel(
    val id: String,
    /** Product name — intentionally not translated. */
    val label: String,
    @StringRes val subtitleRes: Int,
)

const val DEFAULT_LIVE_MODEL = "models/gemini-3.8-live"

val LIVE_MODELS = listOf(
    LiveModel(
        id = DEFAULT_LIVE_MODEL,
        label = "Gemini 3.8 Live",
        subtitleRes = R.string.settings_model_default_sub,
    ),
    LiveModel(
        id = "models/gemini-3.1-flash-live-preview",
        label = "Gemini 3.1 Flash Live",
        subtitleRes = R.string.settings_model_legacy_sub,
    ),
)
