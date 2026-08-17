package query

sealed class QuerySegment {
    data class Collection(
        val name: String,
        val condition: Condition? = null,
        val only: List<String>? = null
    ) : QuerySegment() {
        override fun toString(): String {
            return "$name ${condition ?: ""} ${only ?: ""}"
        }
    }

    data class Connection(
        val connectionName: String,
        val collectionName: String,
        val connectionCondition: Condition? = null,
        val collectionCondition: Condition? = null,
        val connectionOnly: List<String>? = null,
        val collectionOnly: List<String>? = null,
    ) : QuerySegment() {
        override fun toString(): String {
            return "$connectionName $collectionName ${connectionCondition ?: ""} ${collectionCondition ?: ""} ${connectionOnly ?: ""} ${collectionOnly ?: ""}"
        }
    }

    fun collectionName(): String {
        return when (this) {
            is Connection -> collectionName
            is Collection -> name
        }
    }
}