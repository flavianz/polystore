package ch.flavianz.model

import java.util.UUID
import kotlin.collections.plus

data class QueryPath(val segments: List<QuerySegment>){

    constructor(collection: QuerySegment.Collection) : this(listOf(collection))
    constructor(connection: QuerySegment.Connection) : this(listOf(connection))

    fun subCol(name: String): QueryPath {
        return QueryPath(segments + QuerySegment.Collection(name))
    }
    fun con(name: String): QueryPath {
        return QueryPath(segments + QuerySegment.Connection(name))
    }

    fun subCol(collection: QuerySegment.Collection): QueryPath {
        return QueryPath(segments + collection)
    }
    fun con(connection: QuerySegment.Connection): QueryPath {
        return QueryPath(segments + connection)
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }
}

data class CollectionRef(val segments: List<PathSegment.Collection>){

    init {
        require(segments.isNotEmpty()) {"collection ref cannot be empty"}
    }

    fun sub(name: String): CollectionRef {
        return CollectionRef(segments + PathSegment.Collection(name))
    }

    constructor(name: String) : this(listOf(PathSegment.Collection(name)))

    fun toPostgresPath(): String {
        return segments.joinToString("_")
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }

}

data class CollectionPath(val segments: List<PathSegment>) {

    init {
        require(segments.isNotEmpty()) {"collection path can't be empty"}
        require(segments.last() is PathSegment.Collection) {"collection path must end with a Collection Segment"}

        var wasLastCollection = false
        for(segment in segments) {
            require(!(segment is PathSegment.Collection && wasLastCollection)
                    && !(segment is PathSegment.Document && !wasLastCollection)) {"Paths need to be collection and object alternating"}
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
        require(segments.isNotEmpty()) {"object path can't be empty"}
        require(segments.last() is PathSegment.Document) {"object path must end with a Object Segment"}
        var wasLastCollection = false
        for(segment in segments) {
            require(!(segment is PathSegment.Collection && wasLastCollection)
                    && !(segment is PathSegment.Document && !wasLastCollection)) {"Paths need to be collection and object alternating"}
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