package com.geno.veyra.di

import com.geno.veyra.tools.AgentTool
import com.geno.veyra.tools.DeviceStatusTool
import com.geno.veyra.tools.ListConversationsTool
import com.geno.veyra.tools.ListMemoriesTool
import com.geno.veyra.tools.MessageTool
import com.geno.veyra.tools.MicMuteTool
import com.geno.veyra.tools.NavigationTool
import com.geno.veyra.tools.PlacesTool
import com.geno.veyra.tools.SaveMemoryTool
import com.geno.veyra.tools.SearchConversationsTool
import com.geno.veyra.tools.VisionTool
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Registers the function tools into the [Set] that [ToolRegistry] consumes.
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

    @Binds
    @IntoSet
    abstract fun saveMemoryTool(tool: SaveMemoryTool): AgentTool

    @Binds
    @IntoSet
    abstract fun listMemoriesTool(tool: ListMemoriesTool): AgentTool

    @Binds
    @IntoSet
    abstract fun micMuteTool(tool: MicMuteTool): AgentTool

    @Binds
    @IntoSet
    abstract fun searchConversationsTool(tool: SearchConversationsTool): AgentTool

    @Binds
    @IntoSet
    abstract fun listConversationsTool(tool: ListConversationsTool): AgentTool

    @Binds
    @IntoSet
    abstract fun deviceStatusTool(tool: DeviceStatusTool): AgentTool
}
