package ch.flavianz.model

data class CollectionModel(
    val name: String,
    val schema: ObjectSchema,
    val subCollections: MutableMap<String, CollectionModel> = mutableMapOf()
)