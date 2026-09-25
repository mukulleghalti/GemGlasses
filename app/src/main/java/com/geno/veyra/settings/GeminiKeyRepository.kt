package com.geno.veyra.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's own Gemini API key, encrypted at rest with
 * AndroidX Security.
 *
 * The key never leaves the phone: ephemeral Live tokens are minted
 * on-device directly against Google's `auth_tokens` endpoint, and
 * Maps-grounded place search calls `generateContent` directly too.
 * Whatever the user types is stored verbatim — no format checks —
 * and passed through to Google on the next token/places call.
 */
@Singleton
class GeminiKeyRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "gemini_api_key_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The saved key, or null when the user hasn't added one yet. */
    fun getKey(): String? =
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = getKey() != null

    fun saveKey(key: String) {
        // Stored exactly as entered — no trimming or format checks.
        // Whatever the user types goes through to Google's token call,
        // and the response is surfaced back in Settings.
        prefs.edit().putString(KEY, key).apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "gemini_api_key"
    }
}
