package ch.flavianz.core

import ch.flavianz.data.CollectionRef
import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.exceptions.CollectionNotFoundException
import ch.flavianz.query.CreateQuery
import ch.flavianz.driver.DatabaseDriver

object DatabaseManager {
    var rootCollections = mutableMapOf<String, CollectionModel>()

    fun initRootCollections(rootCollections: MutableMap<String, CollectionModel>) {
        this.rootCollections = rootCollections
    }

    fun createCollection(createQuery: CreateQuery) {
        if(!existsCollection(createQuery.parentCollection)) {
            // parent collection does not exist
            throw CollectionNotFoundException(createQuery.parentCollection)
        }
        if(existsCollection(createQuery.parentCollection.sub(createQuery.collectionModel.name))) {
            // collection to be created already exists
            throw CollectionAlreadyExistsException(createQuery.parentCollection.sub(createQuery.collectionModel.name))
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(createQuery) }
    }

    fun registerCollection(collectionModel: CollectionModel, parentCollectionRef: CollectionRef) {
        var currentCollections = rootCollections

        for(collectionName in parentCollectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: throw CollectionNotFoundException(parentCollectionRef)).subCollections
        }
        currentCollections[collectionModel.name] = collectionModel
    }

    private fun existsCollection(collectionRef: CollectionRef): Boolean {
        var currentCollections = rootCollections
        for(collectionName in collectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: return false).subCollections
        }
        return true
    }

    /*fun insertObject(collection: String, data: DataObject) {
        val collectionPath = collection.split(".")

        println(collectionPath)

        var availableCollections = rootCollections.values

        for(pathSegment in collectionPath) {
            if()
        }
    }*/
}