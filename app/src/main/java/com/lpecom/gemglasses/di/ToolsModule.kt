package com.lpecom.gemglasses.di

import com.lpecom.gemglasses.tools.AgentTool
import com.lpecom.gemglasses.tools.MessageTool
import com.lpecom.gemglasses.tools.NavigationTool
import com.lpecom.gemglasses.tools.PlacesTool
import com.lpecom.gemglasses.tools.VisionTool
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Registers the four MVP tools into the [Set] that [ToolRegistry] consumes.
 * Adding a tool means adding a binding here — and updating the project spec.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {

    @Binds
    @IntoSet
    abstract fun visionTool(tool: VisionTool): AgentTool

    @Binds
    @IntoSet
    abstract fun navigationTool(tool: NavigationTool): AgentTool

    @Binds
    @IntoSet
    abstract fun placesTool(tool: PlacesTool): AgentTool

    @Binds
    @IntoSet
    abstract fun messageTool(tool: MessageTool): AgentTool
}
