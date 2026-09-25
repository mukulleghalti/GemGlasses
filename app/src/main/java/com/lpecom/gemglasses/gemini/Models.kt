package com.lpecom.gemglasses.gemini

/**
 * Single source of truth for Gemini model identifiers.
 *
 * Live model IDs churn frequently as Google promotes previews to GA. Keep the
 * value here and nowhere else — never inline a model string in another file.
 *
 * Verified working on the developer (free) tier as of 2025-06. If the Live
 * session fails to start with a 404/NOT_FOUND, the ID has likely rotated;
 * update it here and note the new one in the README.
 */
object Models {
    /** Native-audio Live model: speech-to-speech, VAD, barge-in in one socket. */
    const val GEMINI_LIVE_MODEL = "gemini-3.8-live"

    /** REST model used for `search_places` (generateContent + Maps grounding). */
    const val GEMINI_GROUNDING_MODEL = "gemini-2.5-flash"

    /** BidiGenerateContent WebSocket endpoint (host only; token is a query param). */
    const val LIVE_WS_HOST =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
}
