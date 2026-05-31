package ch.flavianz.model

import ch.flavianz.data.PolyData

data class ConnectionModel(
    val name: String,
    val collection1: CollectionRef,
    val collection2: CollectionRef,
    val connectionDataSchema: PolySchema
) {
    fun toPostgresPath(): String {
        return "${collection1.toPostgresPath()}__${name}__${collection2.toPostgresPath()}"
    }
}
