package com.geno.veyra.tools

import com.geno.veyra.alerts.AlertScheduler
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.math.max

/**
 * `set_timer` — starts a countdown timer. When it fires, the phone speaks
 * the label aloud (through the glasses when they are the audio route) and
 * posts a notification.
 */
class SetTimerTool @Inject constructor(
    private val scheduler: AlertScheduler,
) : AgentTool {

    override val name = "set_timer"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Starts a countdown timer. Call when the user asks " +
            "for a timer ('set a timer for 10 minutes', 'remind me in 20 " +
            "seconds'). When it fires, the label is spoken aloud and a " +
            "notification is posted.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "durationSeconds": {
                  "type": "number",
                  "description": "How long until the timer fires, in seconds. Must be positive and at most 86400 (24 hours)."
                },
                "label": {
                  "type": "string",
                  "description": "What the timer is for, spoken when it fires. E.g. 'pasta'."
                }
              },
              "required": ["durationSeconds"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val seconds =
            args.getValue("durationSeconds").jsonPrimitive.double.toLong()
        val label = args["label"]?.jsonPrimitive?.content.orEmpty()

        if (seconds <= 0 || seconds > MAX_TIMER_SECONDS) {
            return buildJsonObject {
                put("status", "error")
                put(
                    "error",
                    "durationSeconds must be between 1 and " +
                        "$MAX_TIMER_SECONDS",
                )
            }
        }

        val id = scheduler.schedule(
            kind = "timer",
            triggerAtMillis = System.currentTimeMillis() + seconds * 1000,
            label = label,
        )

        return buildJsonObject {
            put("status", "ok")
            put("id", id)
            put("firesInSeconds", seconds)
            put("label", label.ifBlank { "Timer" })
        }
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400L
    }
}

/**
 * `set_reminder` — reminds the user of something at a specific time.
 * The current date/time is in the system instruction; convert what the
 * user says ('at 6pm', 'tomorrow morning') to epoch milliseconds.
 */
class SetReminderTool @Inject constructor(
    private val scheduler: AlertScheduler,
) : AgentTool {

    override val name = "set_reminder"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Schedules a reminder for a specific time. The " +
            "current date and time are in the system instruction — convert " +
            "the user's phrasing ('at 6pm', 'in 20 minutes', 'tomorrow at " +
            "9am') to triggerAtMillis as epoch milliseconds. When it " +
            "fires, the message is spoken aloud and a notification is " +
            "posted.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "triggerAtMillis": {
                  "type": "number",
                  "description": "When the reminder fires, as epoch milliseconds. Must be in the future."
                },
                "message": {
                  "type": "string",
                  "description": "What to remind the user about, spoken when it fires."
                }
              },
              "required": ["triggerAtMillis", "message"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val triggerAt =
            args.getValue("triggerAtMillis").jsonPrimitive.double.toLong()
        val message = args.getValue("message").jsonPrimitive.content

        if (message.isBlank()) {
            return buildJsonObject {
                put("status", "error")
                put("error", "message must not be empty")
            }
        }

        val id = try {
            scheduler.schedule(
                kind = "reminder",
                triggerAtMillis = triggerAt,
                label = message,
            )
        } catch (e: IllegalArgumentException) {
            return buildJsonObject {
                put("status", "error")
                put("error", e.message ?: "trigger time must be in the future")
            }
        }

        return buildJsonObject {
            put("status", "ok")
            put("id", id)
            put("triggerAtMillis", triggerAt)
            put(
                "firesInSeconds",
                max(
                    0,
                    (triggerAt - System.currentTimeMillis()) / 1000,
                ),
            )
            put("message", message)
        }
    }
}

/** `list_alerts` — lists pending timers and reminders. */
class ListAlertsTool @Inject constructor(
    private val scheduler: AlertScheduler,
) : AgentTool {

    override val name = "list_alerts"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Lists pending timers and reminders with their ids, " +
            "labels, and how soon they fire. Call when the user asks what " +
            "timers or reminders are set.",
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
        val now = System.currentTimeMillis()
        val alerts = scheduler.list()

        return buildJsonObject {
            put("status", "ok")
            put(
                "alerts",
                buildJsonArray {
                    alerts.forEach { alert ->
                        addJsonObject {
                            put("id", alert.id)
                            put("kind", alert.kind)
                            put("label", alert.label)
                            put("triggerAtMillis", alert.triggerAtMillis)
                            put(
                                "firesInSeconds",
                                max(0, (alert.triggerAtMillis - now) / 1000),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** `cancel_alert` — cancels a pending timer or reminder by id. */
class CancelAlertTool @Inject constructor(
    private val scheduler: AlertScheduler,
) : AgentTool {

    override val name = "cancel_alert"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Cancels a pending timer or reminder. Use the id " +
            "from set_timer / set_reminder / list_alerts. Call when the " +
            "user asks to cancel or delete a timer or reminder.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "id": {
                  "type": "string",
                  "description": "The alert id returned when it was created."
                }
              },
              "required": ["id"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val id = args.getValue("id").jsonPrimitive.content
        val cancelled = scheduler.cancel(id)

        return buildJsonObject {
            put("status", "ok")
            put("cancelled", cancelled)
            if (!cancelled) {
                put("note", "no alert found with that id")
            }
        }
    }
}
