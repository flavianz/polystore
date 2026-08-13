package model

import kotlinx.serialization.Serializable

@Serializable
data class DatabaseSchema(
    val collections: Set<CollectionModel>,
    val connections: Set<ConnectionModel>
)