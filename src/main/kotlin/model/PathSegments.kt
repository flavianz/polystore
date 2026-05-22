package ch.flavianz.model

import ch.flavianz.query.Condition
import java.util.UUID

sealed class PathSegment {
    data class Collection(val name: String) : PathSegment()
    data class Connection(val name: String) : PathSegment()
    data class Document(val uuid: UUID) : PathSegment()
}

sealed class QuerySegment {
    data class Collection(
        val name: String,
        val condition: Condition? = null
    ) : QuerySegment()

    data class Connection(
        val name: String,
        val condition: Condition? = null
    ) : QuerySegment()
}