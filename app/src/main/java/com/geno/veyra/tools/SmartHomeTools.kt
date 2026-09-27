package com.geno.veyra.tools

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.smarthome.HomeDeviceInfo
import com.geno.veyra.smarthome.SmartHomeController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

private fun ok(message: String): JsonObject =
    buildJsonObject {
        put("status", "ok")
        put("message", message)
    }

private fun toolError(message: String): JsonObject =
    buildJsonObject {
        put("status", "error")
        put("message", message)
    }

private fun HomeDeviceInfo.toJson() =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("home", structureName)
        put("type", typeName)
        put("state", stateSummary)
        put("can_turn_on_off", canTurnOnOff)
        put("can_dim", canDim)
    }

/**
 * `list_home_devices` — lists every Google Home device the user granted
 * Veyra access to, with names, rooms, types, and current states. Gated
 * behind the "Smart Home" AI Settings toggle.
 */
class ListHomeDevicesTool @Inject constructor(
    private val smartHome: SmartHomeController,
) : AgentTool {

    override val name = "list_home_devices"

    override val gate = ToolGate.SMART_HOME

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Lists the user's Google Home devices: names, homes, " +
            "types, and current states (on/off, brightness). Call this " +
            "before controlling a device, or when the user asks what " +
            "devices are available or about a device's state. Returns an " +
            "empty list when Google Home is not connected.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val devices = runCatching { smartHome.listDevices() }.getOrElse {
            return toolError(
                "Couldn't reach Google Home. The user may need to " +
                    "reconnect in Settings.",
            )
        }
        return buildJsonObject {
            put("status", "ok")
            put(
                "devices",
                buildJsonArray {
                    devices.forEach { addJsonObject { it.toJson() } }
                },
            )
        }
    }
}

/**
 * `control_home_device` — turns a Google Home device on/off or sets its
 * brightness. Resolves the spoken name to a device id first; asks for
 * clarification when several devices match. Door locks are always refused.
 * Gated behind the "Smart Home" AI Settings toggle.
 */
class ControlHomeDeviceTool @Inject constructor(
    private val smartHome: SmartHomeController,
) : AgentTool {

    override val name = "control_home_device"

    override val gate = ToolGate.SMART_HOME

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Controls one Google Home device. Use the device " +
            "name as the user said it (or an id from list_home_devices). " +
            "Commands: 'on', 'off', 'brightness' (needs value 0-100). " +
            "Prefer calling list_home_devices first when unsure which " +
            "device the user means.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "device": {
                  "type": "string",
                  "description": "Device name as spoken, or a device id."
                },
                "command": {
                  "type": "string",
                  "enum": ["on", "off", "brightness"]
                },
                "value": {
                  "type": "number",
                  "description": "Brightness percent 0-100, for 'brightness'."
                }
              },
              "required": ["device", "command"]
            }
            """,
        ),
    )

    /** Guards against the model firing the same command twice in a row. */
    private val lastCommands = mutableMapOf<String, Pair<String, Long>>()

    override suspend fun execute(args: JsonObject): JsonObject {
        val query = args.optString("device", "")
        val command = args.optString("command", "").lowercase()
        if (query.isBlank()) {
            return toolError("No device named. Ask which device to control.")
        }
        if (command !in setOf("on", "off", "brightness")) {
            return toolError("Unknown command '$command'. Use on, off, or brightness.")
        }

        val match = runCatching { smartHome.findDevice(query) }.getOrElse {
            return toolError("Couldn't reach Google Home. Try again in a moment.")
        }
        val device = when (match) {
            is SmartHomeController.DeviceMatch.Single -> match.device
            is SmartHomeController.DeviceMatch.Ambiguous ->
                return toolError(
                    "Several devices match '$query': " +
                        match.names.joinToString(", ") +
                        ". Ask the user which one they mean.",
                )
            is SmartHomeController.DeviceMatch.None ->
                return if (match.reason == "no_devices") {
                    toolError(
                        "Google Home isn't connected or has no devices. " +
                            "Ask the user to connect it in Settings.",
                    )
                } else {
                    toolError(
                        "No device matches '$query'. " +
                            "Call list_home_devices to see available names.",
                    )
                }
        }

        if (device.isLock) {
            return toolError(
                "Locks can't be controlled by voice for safety. " +
                    "Use the Google Home app instead.",
            )
        }

        val dedupeKey = "$command:${args["value"]}"
        val now = System.currentTimeMillis()
        val last = lastCommands[device.id]
        if (last != null && last.first == dedupeKey && now - last.second < 3_000) {
            return ok("${device.name}: already applied.")
        }

        val applied = when (command) {
            "on", "off" -> {
                if (!device.canTurnOnOff) {
                    return toolError(
                        "${device.name} (${device.typeName}) can't be " +
                            "turned ${command}.",
                    )
                }
                smartHome.setOnOff(device.id, command == "on")
            }
            else -> {
                val percent = (args["value"] as? JsonPrimitive)
                    ?.doubleOrNull?.toInt()
                    ?: return toolError(
                        "Brightness needs a value from 0 to 100.",
                    )
                if (!device.canDim) {
                    return toolError(
                        "${device.name} (${device.typeName}) doesn't " +
                            "support brightness.",
                    )
                }
                smartHome.setBrightness(device.id, percent)
            }
        }
        return if (applied) {
            lastCommands[device.id] = dedupeKey to now
            val detail = when (command) {
                "on" -> "turned on"
                "off" -> "turned off"
                else -> "brightness set"
            }
            ok("${device.name}: $detail.")
        } else {
            toolError(
                "The command reached ${device.name} but didn't apply. " +
                    "It may be offline.",
            )
        }
    }
}
