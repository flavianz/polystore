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

class QueryPath private constructor(val segments: List<QuerySegment>){

    constructor(collection: QuerySegment.Collection) : this(listOf(collection))
    constructor(connection: QuerySegment.Connection) : this(listOf(connection))

    fun col(name: String): QueryPath {
        return QueryPath(segments + QuerySegment.Collection(name))
    }
    fun con(name: String): QueryPath {
        return QueryPath(segments + QuerySegment.Connection(name))
    }

    fun col(collection: QuerySegment.Collection): QueryPath {
        return QueryPath(segments + collection)
    }
    fun con(connection: QuerySegment.Connection): QueryPath {
        return QueryPath(segments + connection)
    }
}

class CollectionRef private constructor(val segments: List<QuerySegment> = listOf()){

    fun col(name: String): CollectionRef {
        return CollectionRef(segments + QuerySegment.Collection(name))
    }
    fun con(name: String): CollectionRef {
        return CollectionRef(segments + QuerySegment.Connection(name))
    }
}