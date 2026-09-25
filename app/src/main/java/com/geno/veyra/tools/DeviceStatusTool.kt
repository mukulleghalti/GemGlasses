package com.geno.veyra.tools

import android.content.Context
import android.os.BatteryManager
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.glasses.ConnectionState
import com.geno.veyra.glasses.GlassesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `get_device_status` — phone battery level/charging state plus whether the
 * Meta glasses are currently connected.
 *
 * The Meta DAT SDK does not expose the glasses' own battery percentage, so
 * this reports what the phone can actually know: its own battery via
 * BatteryManager, and the glasses link state from GlassesManager.
 */
class DeviceStatusTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glassesManager: GlassesManager,
) : AgentTool {

    override val name = "get_device_status"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Reports device status: the phone's battery " +
            "percentage and whether it is charging, plus whether the " +
            "Meta glasses are currently connected. Call when the user " +
            "asks about battery level, charging, or whether their " +
            "glasses are connected. Note: the glasses' own battery " +
            "percentage is not exposed by the SDK, so never invent a " +
            "glasses battery number — report the phone battery and the " +
            "glasses connection state as returned.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val batteryManager =
            context.getSystemService(BatteryManager::class.java)

        val level = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1

        val charging = batteryManager?.isCharging == true

        val glassesState = try {
            glassesManager.connectionState.first()
        } catch (e: Exception) {
            ConnectionState.DISCONNECTED
        }

        return buildJsonObject {
            put("phoneBatteryPercent", level)
            put("phoneCharging", charging)
            put("glassesConnection", glassesState.name)
            put(
                "glassesConnected",
                glassesState == ConnectionState.CONNECTED,
            )
        }
    }
}
