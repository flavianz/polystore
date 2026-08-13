package ch.flavianz.query

import query.Condition
import query.GetQuery
import query.QueryPath
import query.QuerySegment

class GetQueryBuilder {
    private var segments = mutableListOf<QuerySegment>()
    private var limit: Int? = null

    fun collection(name: String, condition: Condition? = null, only: List<String>? = null) {
        segments.add(QuerySegment.Collection(name, condition, only))
    }

    fun collection(name: String, condition: Condition? = null, only: String) {
        segments.add(QuerySegment.Collection(name, condition, listOf(only)))
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

    fun limit(value: Int) {
        limit = value
    }

    fun build(): GetQuery {
        return GetQuery(
            QueryPath(segments), limit
        )
    }
}

fun get(block: GetQueryBuilder.() -> Unit): GetQuery {
    return GetQueryBuilder().apply(block).build()
}