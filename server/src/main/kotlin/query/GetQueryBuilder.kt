package ch.flavianz.query

import ch.flavianz.model.GetQuery
import ch.flavianz.model.QuerySegment

class GetQueryBuilder {
    private var segments = mutableListOf<QuerySegment>()

    fun collection(name: String, condition: Condition? = null, only: List<String>? = null) {
        segments.add(QuerySegment.Collection(name, condition, only))
    }

    fun connection(
        connectionName: String,
        collectionName: String,
        connectionCondition: Condition? = null,
        collectionCondition: Condition? = null,
        connectionOnly: List<String>? = null,
        collectionOnly: List<String>? = null,
    ) {
        segments.add(
            QuerySegment.Connection(
                connectionName,
                collectionName,
                connectionCondition,
                collectionCondition,
                connectionOnly,
                collectionOnly
            )
        )
    }

    fun build(): GetQuery {
        return GetQuery(
            segments
        )
    }
}

fun query(block: GetQueryBuilder.() -> Unit): GetQuery {
    return GetQueryBuilder().apply(block).build()
}