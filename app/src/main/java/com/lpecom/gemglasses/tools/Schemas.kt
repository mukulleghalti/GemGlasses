package com.lpecom.gemglasses.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parses an OpenAPI-subset JSON schema literal into a [JsonElement]. */
internal fun schema(json: String): JsonElement = Json.parseToJsonElement(json.trimIndent())

private fun JsonObject.string(key: String): String? =
    when (val el = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

/** Reads a required string argument, throwing a clear error if missing/blank. */
internal fun JsonObject.requireString(key: String): String {
    val value = string(key)
    require(!value.isNullOrBlank()) { "Missing required argument '$key'" }
    return value
}

/** Reads an optional string argument with a fallback. */
internal fun JsonObject.optString(key: String, default: String): String =
    string(key)?.takeIf { it.isNotBlank() } ?: default
