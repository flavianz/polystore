package ch.flavianz.data

class CollectionRef(val collectionPath: List<String>) {

    fun sub(name: String) {
        return CollectionRef(listOf(collectionPath.))
    }

    constructor(path: String) : this(path.split("."))
}