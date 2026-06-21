package ch.flavianz.model

import kotlinx.serialization.Serializable

@Serializable
data class CollectionModel(
    val name: String,
    val schema: PolySchema,
    val childCollections: MutableList<String> = mutableListOf(),
    val parentCollection: String?
) {
    fun hasParentCollection(): Boolean = parentCollection != null
}

typealias PolySchema = Map<String, DataType>

fun PolySchema.toJson(): String {
    return "[${entries.joinToString(",") { "{\"name\": \"${it.key}\", \"type\": \"${it.value}\"}" }}]"
}

data class DatabaseSchema(
    val collections: Set<CollectionModel>,
    val connections: Set<ConnectionModel>
)