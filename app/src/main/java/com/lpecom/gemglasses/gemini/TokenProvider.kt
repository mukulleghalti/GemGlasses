package com.lpecom.gemglasses.gemini

import android.util.Log
import com.lpecom.gemglasses.settings.GeminiKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mints short-lived Gemini Live ephemeral tokens **on the device**.
 *
 * This is the same `auth_tokens` call the old Cloudflare backend used to
 * make — a plain HTTPS POST with the user's API key in the
 * `x-goog-api-key` header (never the query string, so the key can't leak
 * into logged URLs). The returned token is single-use, scoped to the
 * Live API, and expires quickly; the full-privilege API key itself never
 * leaves the phone.
 *
 * Whatever the user typed into Settings is sent verbatim — no format
 * pre-checks. A bad key surfaces here as a 400 from Google, which the
 * caller reports as a session error.
 */
@Singleton
class TokenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyRepository: GeminiKeyRepository,
) {
    @Serializable
    private data class TokenResponse(
        val token: String? = null,
        val name: String? = null,
        val expireTime: String? = null,
    )

    /** Thrown when no API key has been saved in Settings yet. */
    class MissingApiKeyException :
        IllegalStateException("No Gemini API key saved — add one in Settings.")

    /**
     * Whether we currently hold a live (unexpired) ephemeral token.
     * True after any successful mint — a session start or a Settings
     * "Save & test" — and stays true until the token's ~30 min window
     * passes without a fresh mint. The home screen's green/red dot
     * reads this: green means Gemini is actually reachable right now.
     */
    private val _hasLiveToken = MutableStateFlow(false)
    val hasLiveToken: StateFlow<Boolean> = _hasLiveToken.asStateFlow()

    @Volatile
    private var lastMintAtMs: Long = 0L

    /**
     * Clock-accurate version of [hasLiveToken]: the flow is the reactive
     * signal (it fires on every mint), this guards against a token that
     * expired while the app sat idle.
     */
    fun hasLiveTokenNow(): Boolean =
        System.currentTimeMillis() - lastMintAtMs < TOKEN_TTL_MS

    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        val apiKey = keyRepository.getKey() ?: throw MissingApiKeyException()

        // ~30 min window to start a session; a single new session may be opened.
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("expireTime", Instant.ofEpochMilli(now + 30 * 60_000).toString())
            put("newSessionExpireTime", Instant.ofEpochMilli(now + 2 * 60_000).toString())
            put("uses", 1)
        }

        val request = Request.Builder()
            .url("$GEMINI_API/auth_tokens")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Google's error bodies never contain the key when it's
                    // sent via header, so the summary is safe to surface.
                    throw IllegalStateException(
                        "Token request failed (${response.code}): " +
                            describeError(response.code, body),
                    )
                }
                val tokenResp =
                    json.decodeFromString(TokenResponse.serializer(), body)
                val token = tokenResp.token ?: tokenResp.name
                check(!token.isNullOrBlank()) {
                    "Google returned 200 but no token in the response"
                }
                _hasLiveToken.value = true
                lastMintAtMs = System.currentTimeMillis()
                return@withContext token
            }
        } catch (e: Exception) {
            // Never log the key — only the failure reason.
            Log.e("TokenDebug", "fetchEphemeralToken failed: ${e.message}")
            throw e
        }
    }

    /**
     * Turns Google's status code + error body into a message the user can
     * act on. The body is truncated so a long upstream payload can't flood
     * the UI.
     */
    private fun describeError(code: Int, body: String): String {
        val detail = body.take(300).replace(Regex("\\s+"), " ").trim()
        return when (code) {
            400 -> "the API key was rejected by Google. $detail"
            401, 403 -> "Google refused the API key (not authorized). $detail"
            429 -> "API quota exceeded for this key — try again later. $detail"
            else -> detail.ifEmpty { "unexpected response" }
        }
    }

    private companion object {
        const val GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"
        val JSON_MEDIA = "application/json".toMediaType()

        /**
         * How long a minted token counts as "live" for the status dot.
         * Google's expireTime is ~30 min; we stay conservative.
         */
        const val TOKEN_TTL_MS = 25 * 60_000L
    }
}
