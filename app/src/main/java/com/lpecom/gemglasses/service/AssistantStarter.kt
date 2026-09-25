package com.lpecom.gemglasses.service

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.lpecom.gemglasses.agent.AgentController
import com.lpecom.gemglasses.state.ConversationStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the assistant session — the exact same path as the HomeScreen
 * "Start Assistant" button. [com.lpecom.gemglasses.ui.AgentViewModel.startSession]
 * delegates here, and [com.lpecom.gemglasses.wakeword.WakeWordService] uses it
 * when the wake word is detected, so both triggers stay identical.
 */
@Singleton
class AssistantStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AgentController,
    private val conversation: ConversationStore,
) {

    /** Caller must hold [Manifest.permission.RECORD_AUDIO]. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        conversation.clear()
        AgentForegroundService.start(context)
        controller.start()
    }
}
