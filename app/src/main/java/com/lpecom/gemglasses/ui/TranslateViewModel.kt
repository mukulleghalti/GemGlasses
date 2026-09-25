package com.lpecom.gemglasses.ui

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.settings.SettingsRepository
import com.lpecom.gemglasses.translate.TranslateController
import com.lpecom.gemglasses.translate.TranslateLanguage
import com.lpecom.gemglasses.translate.TranslateStatus
import com.lpecom.gemglasses.translate.TranslationExchange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the Compose UI to the [TranslateController].
 *
 * Owns the selected language pair (persisted) and exposes the running
 * translation session's status and finished exchanges.
 */
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val controller: TranslateController,
    private val settings: SettingsRepository,
) : ViewModel() {

    val status: StateFlow<TranslateStatus> =
        controller.status

    val exchanges: StateFlow<List<TranslationExchange>> =
        controller.exchanges

    private val _sourceLang =
        MutableStateFlow(TranslateLanguage.AUTO)
    val sourceLang: StateFlow<TranslateLanguage> =
        _sourceLang.asStateFlow()

    private val _targetLang =
        MutableStateFlow(
            TranslateLanguage.fromCode("hi"),
        )
    val targetLang: StateFlow<TranslateLanguage> =
        _targetLang.asStateFlow()

    init {
        viewModelScope.launch {
            _sourceLang.value =
                TranslateLanguage.fromCode(
                    settings.translateSourceLang.first(),
                )
            _targetLang.value =
                TranslateLanguage.fromCode(
                    settings.translateTargetLang.first(),
                )
        }
    }

    val listening: Boolean
        get() = controller.running

    /**
     * The controller requires RECORD_AUDIO; the screen requests it before
     * calling this, so the permission is already granted here.
     */
    @SuppressLint("MissingPermission")
    fun toggleListening() {
        if (controller.running) {
            controller.stop()
        } else {
            controller.start(
                _sourceLang.value,
                _targetLang.value,
            )
        }
    }

    fun setSource(language: TranslateLanguage) {
        if (language.code == _targetLang.value.code) {
            // Keep the pair distinct: swap instead of duplicating.
            swapLanguages()
            return
        }
        applyLanguages(
            source = language,
            target = _targetLang.value,
        )
    }

    fun setTarget(language: TranslateLanguage) {
        if (language.code == _sourceLang.value.code) {
            swapLanguages()
            return
        }
        applyLanguages(
            source = _sourceLang.value,
            target = language,
        )
    }

    fun swapLanguages() {
        val source = _sourceLang.value
        val target = _targetLang.value
        // Auto-detect can't be a translation target; keep English instead.
        val newTarget =
            if (source.code == TranslateLanguage.AUTO.code) {
                TranslateLanguage.fromCode("en")
            } else {
                source
            }
        applyLanguages(
            source = target,
            target = newTarget,
        )
    }

    fun clearHistory() {
        controller.clearHistory()
    }

    /**
     * Changing the language pair restarts the session so the new system
     * prompt (and speech language) takes effect immediately.
     *
     * The controller requires RECORD_AUDIO; the screen only offers language
     * changes after the permission is granted.
     */
    @SuppressLint("MissingPermission")
    private fun applyLanguages(
        source: TranslateLanguage,
        target: TranslateLanguage,
    ) {
        _sourceLang.value = source
        _targetLang.value = target
        viewModelScope.launch {
            settings.setTranslateSourceLang(source.code)
            settings.setTranslateTargetLang(target.code)
            if (controller.running) {
                controller.stop()
                controller.start(source, target)
            }
        }
    }

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }
}
