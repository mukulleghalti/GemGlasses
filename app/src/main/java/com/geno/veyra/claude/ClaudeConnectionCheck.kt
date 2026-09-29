package com.geno.veyra.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies an Anthropic API key with a cheap, read-only call:
 * `GET https://api.anthropic.com/v1/models`. Mirrors
 * [com.geno.veyra.openai.OpenAiConnectionCheck].
 *
 * - HTTP 200: the key is valid.
 * - HTTP 401: the key is wrong or revoked.
 * - Anything else / network failure: surfaced as an exception whose
 *   message is shown in Settings.
 */
@Singleton
class ClaudeConnectionCheck @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun verifyKey(key: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", key)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw IOException(
                    "Couldn't reach Anthropic: ${e.message}",
                    e,
                )
            }
            response.use {
                when (it.code) {
                    200 -> Unit
                    401 -> throw IllegalArgumentException(
                        "Anthropic rejected the key (401). " +
                            "Check it at console.anthropic.com.",
                    )
                    else -> throw IllegalStateException(
                        "Anthropic returned HTTP ${it.code}.",
                    )
                }
            }
        }
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
