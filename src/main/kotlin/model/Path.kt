package ch.flavianz.model

import java.util.UUID
import kotlin.collections.plus

data class QueryPath(val segments: List<QuerySegment>) {
    init {
        require(segments.isNotEmpty()) { "query path cannot be empty" }
        require(segments.first() is QuerySegment.Collection) { "first segment of query path must be a collection" }
    }

    constructor(collection: QuerySegment.Collection) : this(listOf(collection))
    constructor(connection: QuerySegment.Connection) : this(listOf(connection))

    fun subCol(name: String): QueryPath {
        return QueryPath(segments + QuerySegment.Collection(name))
    }

    fun con(connectionName: String, collectionName: String): QueryPath {
        return QueryPath(segments + QuerySegment.Connection(connectionName, collectionName))
    }

    fun subCol(collection: QuerySegment.Collection): QueryPath {
        return QueryPath(segments + collection)
    }

    fun con(connection: QuerySegment.Connection): QueryPath {
        return QueryPath(segments + connection)
    }

    fun subPath(n: Int): QueryPath {
        return QueryPath(segments.take(n))
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }
}

data class CollectionRef(val segments: List<PathSegment.Collection>) {

    init {
        require(segments.isNotEmpty()) { "collection ref cannot be empty" }
    }

    fun sub(name: String): CollectionRef {
        return CollectionRef(segments + PathSegment.Collection(name))
    }

    constructor(vararg segments: String) : this(segments.map { PathSegment.Collection(it) })

    fun toPostgresPath(): String {
        return segments.joinToString("_")
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }

}

data class CollectionPath(val segments: List<PathSegment>) {

    init {
        require(segments.isNotEmpty()) { "collection path can't be empty" }
        require(segments.last() is PathSegment.Collection) { "collection path must end with a Collection Segment" }

        var wasLastCollection = false
        for (segment in segments) {
            require(
                !(segment is PathSegment.Collection && wasLastCollection)
                        && !(segment is PathSegment.Document && !wasLastCollection)
            ) { "Paths need to be collection and object alternating" }
            wasLastCollection = segment is PathSegment.Collection
        }
    }

    fun doc(uuid: UUID): DocumentPath {
        return DocumentPath(segments + PathSegment.Document(uuid))
    }

    fun doc(uuid: String): DocumentPath {
        return DocumentPath(segments + PathSegment.Document(UUID.fromString(uuid)))
    }

    fun parentDoc(): DocumentPath {
        val newPath = segments.toMutableList()
        newPath.removeLast()
        return DocumentPath(newPath)
    }

    fun toCollectionRef(): CollectionRef {
        return CollectionRef(segments.filterIsInstance<PathSegment.Collection>())
    }

    constructor(name: String) : this(listOf(PathSegment.Collection(name)))

    override fun toString(): String {
        return segments.joinToString(".")
    }
}

data class DocumentPath(val segments: List<PathSegment>) {
    init {
        require(segments.isNotEmpty()) { "object path can't be empty" }
        require(segments.last() is PathSegment.Document) { "object path must end with a Object Segment" }
        var wasLastCollection = false
        for (segment in segments) {
            require(
                !(segment is PathSegment.Collection && wasLastCollection)
                        && !(segment is PathSegment.Document && !wasLastCollection)
            ) { "Paths need to be collection and object alternating" }
            wasLastCollection = segment is PathSegment.Collection
        }
    }

    val uuid get() = (segments.last() as PathSegment.Document).uuid

    fun sub(name: String): CollectionPath {
        return CollectionPath(segments + PathSegment.Collection(name))
    }

    fun parentCollection(): CollectionPath {
        val newPath = segments.toMutableList()
        newPath.removeLast()
        return CollectionPath(newPath)
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }
}