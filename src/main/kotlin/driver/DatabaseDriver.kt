package ch.flavianz.driver

import ch.flavianz.data.CollectionRef
import ch.flavianz.model.CollectionConnection
import ch.flavianz.query.CreateCollectionQuery

interface DatabaseDriver {
    fun createCollection(createCollectionQuery: CreateCollectionQuery)

    fun createConnection(connection: CollectionConnection)
}
