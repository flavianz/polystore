package ch.flavianz.model

data class CollectionModel(
    val name: String,
    val schema: PolySchema,
    val childCollections: MutableList<String> = mutableListOf()
)

typealias PolySchema = Map<String, DataType>