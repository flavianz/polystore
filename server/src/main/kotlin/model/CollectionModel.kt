package ch.flavianz.model

import ch.flavianz.core.DatabaseManager
import kotlinx.serialization.Serializable

@Serializable
data class CollectionModel(
    val name: String,
    val schema: PolySchema,
    val childCollections: MutableList<String> = mutableListOf(),
    val parentCollection: String?
) {
    fun hasParentCollection(): Boolean = parentCollection != null
    fun getConnectedCollections(): List<String> {
        return DatabaseManager.listConnections().filter { it.collection1Name == name || it.collection2Name == name }
            .map { if (it.collection1Name == name) it.collection2Name else it.collection1Name }
    }
}
