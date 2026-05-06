package core.driver

import ch.flavianz.model.CollectionModel

interface DatabaseDriver {
    fun createCollection(collectionModel: CollectionModel)
}
