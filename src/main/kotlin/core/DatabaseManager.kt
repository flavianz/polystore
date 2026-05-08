package ch.flavianz.core

import ch.flavianz.data.CollectionRef
import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.exceptions.CollectionNotFoundException
import ch.flavianz.query.CreateCollectionQuery
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.exceptions.ConnectionAlreadyExistsException
import ch.flavianz.model.CollectionConnection

object DatabaseManager {
    private var rootCollections = mutableMapOf<String, CollectionModel>()
    private var connections = mutableMapOf<String, CollectionConnection>()

    fun initRootCollections(rootCollections: MutableMap<String, CollectionModel>) {
        this.rootCollections = rootCollections
    }

    fun createCollection(createCollectionQuery: CreateCollectionQuery) {
        if(!existsCollection(createCollectionQuery.parentCollection)) {
            // parent collection does not exist
            throw CollectionNotFoundException(createCollectionQuery.parentCollection)
        }
        if(existsCollection(createCollectionQuery.parentCollection.sub(createCollectionQuery.collectionModel.name))) {
            // collection to be created already exists
            throw CollectionAlreadyExistsException(createCollectionQuery.parentCollection.sub(createCollectionQuery.collectionModel.name))
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(createCollectionQuery) }
    }

    fun createConnection(connection: CollectionConnection){
        if(connections.containsKey(connection.name)) {
            throw ConnectionAlreadyExistsException(connection.name)
        }
        if(!existsCollection(connection.collection1)) {
            throw CollectionNotFoundException(connection.collection1)
        }
        if(!existsCollection(connection.collection2)) {
            throw CollectionNotFoundException(connection.collection2)
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createConnection)(connection) }
    }

    fun registerCollection(collectionModel: CollectionModel, parentCollectionRef: CollectionRef) {
        var currentCollections = rootCollections

        for(collectionName in parentCollectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: throw CollectionNotFoundException(parentCollectionRef)).subCollections
        }
        currentCollections[collectionModel.name] = collectionModel
    }

    fun registerConnection(connection: CollectionConnection) {
        connections[connection.name] = connection
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