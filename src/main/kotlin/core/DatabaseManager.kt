package ch.flavianz.core

import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.data.DataObject
import core.driver.DatabaseDriver

object DatabaseManager {
    private val rootCollections = mutableMapOf<String, CollectionModel>()

    fun createCollection(model: CollectionModel) {
        if(rootCollections.containsKey(model.name)) {
            throw CollectionAlreadyExistsException(model.name)
        }
        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(model) }
        rootCollections[model.name] = model
    }

    fun insertObject(collection: String, data: DataObject) {
        val collectionPath = collection.split(".")

        println(collectionPath)

        var availableCollections = rootCollections.values

        for(pathSegment in collectionPath) {
            if()
        }
    }
}