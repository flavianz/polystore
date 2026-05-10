package ch.flavianz.data

import java.util.LinkedList

class CollectionRef(private val collectionPath: LinkedList<String>) {

    val path get() = this.collectionPath

    fun sub(name: String): CollectionRef {
        val newPath = LinkedList(collectionPath)
        newPath.add(name)
        return CollectionRef(newPath)
    }

    fun parent(): CollectionRef {
        val newPath = LinkedList(collectionPath)
        newPath.removeLast()
        return CollectionRef(newPath)
    }

    fun goToParent() {
        collectionPath.removeLast()
    }

    fun goToSub(name: String) {
        collectionPath.add(name)
    }

    constructor(path: String) : this(LinkedList(path.split(".")))
    constructor() : this(LinkedList(emptyList<String>()))

    fun toPostgresPath(): String {
        return collectionPath.joinToString("_")
    }

    override fun toString(): String {
        return collectionPath.joinToString(".")
    }

    companion object {
        val root = CollectionRef()
    }

    fun isRoot(): Boolean {
        return collectionPath.isEmpty()
    }
}