package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches an ephemeral Gemini Live token from the backend Worker. The Google
 * API key never touches the client — the Worker exchanges our app secret for a
 * short-lived token scoped to the Live API.
 */
@Singleton
class TokenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    @Serializable
    private data class TokenResponse(val token: String, val expiresAt: String? = null)

    /** Returns a fresh ephemeral token, or throws on transport/auth failure. */
    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.BACKEND_URL.trimEnd('/')}/token")
            .header("X-App-Secret", BuildConfig.APP_SECRET)
            .post(ByteArray(0).toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Token endpoint returned ${response.code}: ${body.take(200)}"
            }
            json.decodeFromString(TokenResponse.serializer(), body).token
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
