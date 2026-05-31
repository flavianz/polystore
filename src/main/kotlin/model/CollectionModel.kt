package ch.flavianz.model

data class CollectionModel(
    val name: String,
    val schema: PolySchema,
)

typealias PolySchema = Map<String, DataType>