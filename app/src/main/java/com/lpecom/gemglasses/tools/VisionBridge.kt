package com.lpecom.gemglasses.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indirection that lets [VisionTool] request a vision burst without depending
 * on the session orchestrator (which in turn depends on the tool registry).
 * The orchestrator installs itself as the [delegate] when a session starts.
 */
@Singleton
class VisionBridge @Inject constructor() : VisionController {
    @Volatile var delegate: VisionController? = null

    override fun startVisionBurst(durationMs: Long) {
        delegate?.startVisionBurst(durationMs)
    }
}
