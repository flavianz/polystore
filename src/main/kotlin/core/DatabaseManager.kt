package ch.flavianz.core

import ch.flavianz.core.driver.DriverManager
import ch.flavianz.core.exceptions.CollectionAlreadyExistsException
import ch.flavianz.core.model.CollectionModel
import core.driver.DatabaseDriver

object DatabaseManager {
    private val rootCollections = mutableMapOf<String, CollectionModel>()

    fun createCollection(model: CollectionModel) {
        if(rootCollections.containsKey(model.name)) {
            throw CollectionAlreadyExistsException(model.name)
        }
        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(model) }
    }
}