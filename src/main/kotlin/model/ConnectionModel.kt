package ch.flavianz.model

data class ConnectionModel(
    val name: String,
    val collection1: CollectionRef,
    val collection2: CollectionRef,
    val connectionData: ObjectSchema,
)
