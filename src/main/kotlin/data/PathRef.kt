package ch.flavianz.data

import java.util.LinkedList
import java.util.UUID

sealed class PathSegment {
    data class Collection(val name: String) : PathSegment()
    data class Object(val uuid: UUID) : PathSegment()
}

class CollectionPathRef(val segments: LinkedList<PathSegment>) {

    init {
        if(segments.isEmpty()) {
            throw IllegalStateException("collection path can't be empty")
        }
        if(segments.last() !is PathSegment.Collection) {
            throw IllegalStateException("collection path must end with a Collection Segment")
        }
        var wasLastCollection = false
        for(segment in segments) {
            if((segment is PathSegment.Collection && wasLastCollection) || (segment is PathSegment.Object && !wasLastCollection)) {
                throw IllegalStateException("Paths need to be collection and object alternating")
            }
            wasLastCollection = segment is PathSegment.Collection
        }
    }

    fun doc(uuid: UUID): DocumentPathRef {
        return DocumentPathRef(LinkedList(segments + PathSegment.Object(uuid)))
    }

    fun doc(uuid: String): DocumentPathRef {
        return DocumentPathRef(LinkedList(segments + PathSegment.Object(UUID.fromString(uuid))))
    }

    fun parentDoc(): DocumentPathRef {
        val newPath = LinkedList(segments)
        newPath.removeLast()
        return DocumentPathRef(newPath)
    }

    fun collectionPath(): LinkedList<PathSegment.Collection> {
        return LinkedList(segments.filterIsInstance<PathSegment.Collection>())
    }

    fun toCollectionRef(): CollectionRef {
        return CollectionRef(LinkedList(collectionPath().map { it.name }))
    }

    constructor(name: String) : this(LinkedList(listOf(PathSegment.Collection(name))))
}

class DocumentPathRef(val segments: LinkedList<PathSegment>) {
    init {
        if(segments.isEmpty()) {
            throw IllegalStateException("object path can't be empty")
        }
        if(segments.last() !is PathSegment.Object) {
            throw IllegalStateException("object path must end with a Object Segment")
        }
        var wasLastCollection = false
        for(segment in segments) {
            if((segment is PathSegment.Collection && wasLastCollection) || (segment is PathSegment.Object && !wasLastCollection)) {
                throw IllegalStateException("Paths need to be collection and object alternating")
            }
            wasLastCollection = segment is PathSegment.Collection
        }
    }

    val uuid get() = (segments.last() as PathSegment.Object).uuid

    fun sub(name: String): CollectionPathRef {
        return CollectionPathRef(LinkedList(segments + PathSegment.Collection(name)))
    }
}