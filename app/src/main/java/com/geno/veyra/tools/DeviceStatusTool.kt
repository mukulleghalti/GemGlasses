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
 * `get_device_status` — phone battery level/charging state, the glasses
 * link state, and the glasses' own device state.
 *
 * Since the DAT 1.0 SDK, the glasses report their own battery
 * percentage, charging state, don state (worn or not), hinge state
 * (open or folded), and thermal level through the device metadata that
 * GlassesManager already collects. Those values are included whenever
 * the SDK provides them; a disconnected or unregistered pair simply
 * omits them.
 */
class DeviceStatusTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glassesManager: GlassesManager,
) : AgentTool {

    override val name = "get_device_status"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Reports device status: the phone's battery " +
            "percentage and whether it is charging, the glasses " +
            "connection state, and — when the glasses are connected — " +
            "the glasses' own battery percentage, whether they are " +
            "charging, whether they are being worn, their hinge state, " +
            "and their thermal level. Call when the user asks about " +
            "battery level, charging, glasses temperature, or whether " +
            "their glasses are connected. Report the glasses battery " +
            "only when glassesBatteryPercent is present in the result; " +
            "never invent a glasses battery number when it is absent.",
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

        val glassesDevice = try {
            val known = glassesManager.devices.first()
            known.firstOrNull { it.connected } ?: known.firstOrNull()
        } catch (e: Exception) {
            null
        }

        return buildJsonObject {
            put("phoneBatteryPercent", level)
            put("phoneCharging", charging)
            put("glassesConnection", glassesState.name)
            put(
                "glassesConnected",
                glassesState == ConnectionState.CONNECTED,
            )
            glassesDevice?.let { device ->
                device.batteryPercent?.let {
                    put("glassesBatteryPercent", it)
                }
                device.batteryCharging?.let {
                    put("glassesBatteryCharging", it)
                }
                device.donState?.let {
                    put("glassesDonState", it)
                }
                device.hingeState?.let {
                    put("glassesHingeState", it)
                }
                device.thermalLevel?.let {
                    put("glassesThermalLevel", it)
                }
            }
        }
    }
}
