package core.driver

import ch.flavianz.core.model.CollectionModel

interface DatabaseDriver {
    fun createCollection(collectionModel: CollectionModel)
}
