package ch.flavianz.model

import ch.flavianz.data.CollectionRef

data class CollectionConnection(
    val name: String,
    val collection1: CollectionRef,
    val collection2: CollectionRef,
    val connectionData: ObjectSchema,
)
