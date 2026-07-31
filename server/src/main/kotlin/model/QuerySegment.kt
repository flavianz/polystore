package ch.flavianz.model

import ch.flavianz.query.Condition

sealed class QuerySegment {
    data class Collection(
        val name: String,
        val condition: Condition? = null,
        val only: List<String>? = null
    ) : QuerySegment()

    data class Connection(
        val connectionName: String,
        val collectionName: String,
        val connectionCondition: Condition? = null,
        val collectionCondition: Condition? = null,
        val connectionOnly: List<String>? = null,
        val collectionOnly: List<String>? = null,
    ) : QuerySegment()

    fun collectionName(): String {
        return when (this) {
            is Connection -> collectionName
            is Collection -> name
        }
    }
}