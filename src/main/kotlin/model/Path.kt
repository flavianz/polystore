package ch.flavianz.model

import java.util.LinkedList
import java.util.UUID
import kotlin.collections.plus

class QueryPath(val segments: List<QuerySegment>){

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

    override fun equals(other: Any?): Boolean {
        if(other !is QueryPath) return false
        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}

class CollectionRef(val segments: List<PathSegment.Collection> = listOf()){

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

    override fun equals(other: Any?): Boolean {
        if(other !is CollectionRef) return false
        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}

class CollectionPath(val segments: List<PathSegment>) {

    init {
        if(segments.isEmpty()) {
            throw IllegalStateException("collection path can't be empty")
        }
        if(segments.last() !is PathSegment.Collection) {
            throw IllegalStateException("collection path must end with a Collection Segment")
        }
        var wasLastCollection = false
        for(segment in segments) {
            if((segment is PathSegment.Collection && wasLastCollection) || (segment is PathSegment.Document && !wasLastCollection)) {
                throw IllegalStateException("Paths need to be collection and object alternating")
            }
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


    override fun equals(other: Any?): Boolean {
        if(other !is CollectionPath) return false
        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}

class DocumentPath(val segments: List<PathSegment>) {
    init {
        if(segments.isEmpty()) {
            throw IllegalStateException("object path can't be empty")
        }
        if(segments.last() !is PathSegment.Document) {
            throw IllegalStateException("object path must end with a Object Segment")
        }
        var wasLastCollection = false
        for(segment in segments) {
            if((segment is PathSegment.Collection && wasLastCollection) || (segment is PathSegment.Document && !wasLastCollection)) {
                throw IllegalStateException("Paths need to be collection and object alternating")
            }
            wasLastCollection = segment is PathSegment.Collection
        }
    }

    val uuid get() = (segments.last() as PathSegment.Document).uuid

    fun sub(name: String): CollectionPath {
        return CollectionPath(segments + PathSegment.Collection(name))
    }

    fun parentCollection(): CollectionPath {
        val newPath = LinkedList(segments)
        newPath.removeLast()
        return CollectionPath(newPath)
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }

    override fun equals(other: Any?): Boolean {
        if(other !is DocumentPath) return false
        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}