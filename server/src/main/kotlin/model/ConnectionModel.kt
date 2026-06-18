package ch.flavianz.model

data class ConnectionModel(
    val name: String,
    val collection1Name: String,
    val collection2Name: String,
    val connectionDataSchema: PolySchema
) {
    fun toPostgresPath(): String {
        return "${collection1Name}__${name}__${collection2Name}"
    }
}
