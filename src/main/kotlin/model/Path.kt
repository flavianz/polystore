package ch.flavianz.model

import java.util.LinkedList
import java.util.UUID
import kotlin.collections.plus

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

class CollectionRef(val segments: List<QuerySegment> = listOf()){

    fun col(name: String): CollectionRef {
        return CollectionRef(segments + QuerySegment.Collection(name))
    }
    fun con(name: String): CollectionRef {
        return CollectionRef(segments + QuerySegment.Connection(name))
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

    fun collectionRef(): CollectionRef {
        return CollectionRef(segments.filterIsInstance<PathSegment.Collection>().map { QuerySegment.Collection(it.name) })
    }

    constructor(name: String) : this(listOf(PathSegment.Collection(name)))
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
}