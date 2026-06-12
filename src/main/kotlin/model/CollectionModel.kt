package ch.flavianz.model

data class CollectionModel(
    val name: String,
    val schema: PolySchema,
    val childCollections: MutableList<String> = mutableListOf(),
    val parentCollection: String?
) {
    fun hasParentCollection(): Boolean = parentCollection != null
}

typealias PolySchema = Map<String, DataType>