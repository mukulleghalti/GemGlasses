package com.geno.veyra.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's own OpenAI API key, encrypted at rest with
 * AndroidX Security — the same pattern as [GeminiKeyRepository].
 *
 * The key never leaves the phone except in direct calls to OpenAI's
 * API (key verification hits `https://api.openai.com/v1/models`).
 * Whatever the user types is stored verbatim — no format checks —
 * and the verification response is surfaced back in Settings.
 */
@Singleton
class OpenAiKeyRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "openai_api_key_store",
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
        prefs.edit().putString(KEY, key).apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "openai_api_key"
    }
}
