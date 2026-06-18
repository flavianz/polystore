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

    fun leafName(): String {
        return segments[segments.size - 1].name
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

    fun hasParentDoc(): Boolean {
        return segments.size > 1
    }

    fun parentDoc(): DocumentPath {
        val newPath = segments.toMutableList()
        newPath.removeLast()
        return DocumentPath(newPath)
    }

    fun toCollectionRef(): CollectionRef {
        return CollectionRef(segments.filterIsInstance<PathSegment.Collection>())
    }

    constructor(vararg segment: String) : this(parsePath(segment.toList()))

    override fun toString(): String {
        return segments.joinToString(".")
    }

    fun leafName(): String {
        return (segments[segments.size - 1] as PathSegment.Collection).name
    }
}

fun parsePath(segmentStrings: List<String>): List<PathSegment> {
    var isCollection = true
    val segments = mutableListOf<PathSegment>()
    for (segment in segmentStrings) {
        segments.add(if (isCollection) PathSegment.Collection(segment) else PathSegment.Document(UUID.fromString(segment)))
        isCollection = !isCollection
    }
    return segments
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