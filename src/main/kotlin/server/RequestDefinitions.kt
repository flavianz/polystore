package ch.flavianz.server

import ch.flavianz.data.PolyValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

@Serializable
data class CreateCollectionRequest(
    val name: String,
    val fields: List<FieldDefinition>,
    val parentCollection: String? = null
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

fun JsonElement.toPolyValue(): PolyValue = when (this) {
    is JsonPrimitive -> when {
        isString -> PolyValue.of(content)
        booleanOrNull != null -> PolyValue.of(boolean)
        intOrNull != null -> PolyValue.of(int)
        doubleOrNull != null -> PolyValue.of(double)
        else -> throw IllegalArgumentException("Unknown primitive: $this")
    }

    is JsonArray -> throw IllegalArgumentException("Arrays not supported")
    is JsonObject -> throw IllegalArgumentException("Nested objects not supported")
}

@Serializable
data class TakeRequest(
    val path: List<RequestQuerySegment>,
    val take: Map<String, List<String>>?,
    val collect: List<String>?
)

@Serializable
data class RequestQuerySegment(
    val name: String,
    val type: String,
    val condition: String?
)