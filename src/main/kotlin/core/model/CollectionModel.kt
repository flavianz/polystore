package ch.flavianz.core.model

data class CollectionModel(
    val name: String,
    val schema: ObjectSchema,
    val subCollection: List<CollectionModel> = listOf()
)