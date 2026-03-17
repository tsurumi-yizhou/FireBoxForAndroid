package com.firebox.client.internal

import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object FunctionSchemaSupport {
    private val codecJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    private val schemaJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    private val schemaGenerator =
        SerializationClassJsonSchemaGenerator(
            json = schemaJson,
            jsonSchemaConfig = JsonSchemaConfig.Strict,
        )

    fun <T> encode(value: T, serializer: KSerializer<T>): String = codecJson.encodeToString(serializer, value)

    fun <T> decode(rawJson: String, serializer: KSerializer<T>): T = codecJson.decodeFromString(serializer, rawJson)

    fun schema(serializer: KSerializer<*>): String =
        schemaJson.encodeToString(
            JsonElement.serializer(),
            normalizeSchema(
                schemaJson.parseToJsonElement(
                    schemaGenerator.generateSchemaString(serializer.descriptor),
                ),
            ),
        )

    private fun normalizeSchema(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                val normalizedValues =
                    element.mapValues { (_, value) -> normalizeSchema(value) }.toMutableMap()
                val typeElement = element["type"]
                val isObjectSchema =
                    typeElement?.let(::containsObjectType) == true ||
                        element.containsKey("properties") ||
                        element.containsKey("additionalProperties")
                if (isObjectSchema && !normalizedValues.containsKey("additionalProperties")) {
                    normalizedValues["additionalProperties"] = JsonPrimitive(false)
                }
                buildJsonObject {
                    normalizedValues.forEach { (key, value) -> put(key, value) }
                }
            }

            is JsonArray -> JsonArray(element.map(::normalizeSchema))
            else -> element
        }

    private fun containsObjectType(element: JsonElement): Boolean =
        when (element) {
            is JsonPrimitive -> element.content == "object"
            is JsonArray -> element.any(::containsObjectType)
            else -> false
        }
}
