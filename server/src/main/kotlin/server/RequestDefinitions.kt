package ch.flavianz.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull.content
import kotlinx.serialization.json.JsonNull.isString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
data class CreateCollectionRequest(
    val name: String,
    val fields: List<FieldDefinition>,
    val parentCollection: String? = null
)

@Serializable
data class DropCollectionRequest(
    val name: String,
    val recursive: Boolean,
)

@Serializable
data class CreateConnectionRequest(
    val name: String,
    val collection1Name: String,
    val collection2Name: String,
    val fields: List<FieldDefinition>,
)


@Serializable
data class DropConnectionRequest(
    val name: String
)

@Serializable
data class FieldDefinition(
    val name: String,
    val type: String  // or your PolyValue type enum
)

@Serializable
data class InsertDocumentRequest(
    val collection: String,
    val fields: Map<String, JsonElement>,
    val parentDocUuid: String?
)

@Serializable
data class InsertConnectionRequest(
    val connection: String,
    val collection1Name: String,
    val collection1Uuid: String,
    val collection2Name: String,
    val collection2Uuid: String,
    val fields: Map<String, JsonElement>,
)

fun JsonElement.toPolyValue(): Any = when (this) {
    is JsonPrimitive -> when {
        isString -> {
            if (content.length == 36) {
                runCatching {
                    return UUID.fromString(content)
                }.onFailure {
                    return content
                }
            }
            return content
        }

        booleanOrNull != null -> boolean
        intOrNull != null -> int
        doubleOrNull != null -> double
        else -> throw IllegalArgumentException("Unknown primitive: $this")
    }

    is JsonObject -> when (val type = jsonObject["type"]) {
        is JsonPrimitive -> {
            if (type.content == "uuid") {
                return UUID.fromString((jsonObject["value"] as JsonPrimitive).content)
            }
            throw IllegalArgumentException("Nested objects not supported")
        }

        else -> throw IllegalArgumentException("Nested objects not supported")
    }

    is JsonArray -> throw IllegalArgumentException("Arrays not supported")
}

@Serializable
data class QueryRequest(
    val path: List<RequestQuerySegment>
)

@Serializable
data class RequestQuerySegment(
    val name: String,
    val type: String,
    val condition: String?,
    val only: List<String>?,
)