package com.lpecom.gemglasses.gemini

import android.util.Log
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

@Singleton
class TokenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    @Serializable
    private data class TokenResponse(val token: String, val expiresAt: String? = null)

    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        Log.d("TokenDebug", "Connecting to: ${BuildConfig.BACKEND_URL}/token")
        Log.d("TokenDebug", "Using App Secret length: ${BuildConfig.APP_SECRET.length}")

        try {
            val request = Request.Builder()
                .url("${BuildConfig.BACKEND_URL.trimEnd('/')}/token")
                .header("X-App-Secret", BuildConfig.APP_SECRET)
                .post(ByteArray(0).toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d("TokenDebug", "Response Code: ${response.code}, Body: $body")
                
                check(response.isSuccessful) {
                    "Token endpoint returned ${response.code}: ${body.take(200)}"
                }
                
                val tokenResp = json.decodeFromString(TokenResponse.serializer(), body)
                Log.d("TokenDebug", "Successfully parsed token!")
                return@withContext tokenResp.token
            }
        } catch (e: Exception) {
            Log.e("TokenDebug", "EXCEPTION in fetchEphemeralToken: ${e.message}", e)
            throw e
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
