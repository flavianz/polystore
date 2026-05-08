package ch.flavianz.exceptions

import ch.flavianz.data.CollectionRef

data class CollectionAlreadyExistsException(val collectionRef: CollectionRef) : Exception() {
    override fun toString(): String {
        return "Collection already exists: \"$collectionRef\""
    }
}

data class CollectionNotFoundException(val collectionRef: CollectionRef) : Exception() {
    override fun toString(): String {
        return "Collection was not found: \"$collectionRef\""
    }
}