package ch.flavianz.driver

import ch.flavianz.model.CollectionConnection
import ch.flavianz.query.CreateCollectionQuery
import ch.flavianz.query.InsertObjectQuery
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(createCollectionQuery: CreateCollectionQuery)
    fun createConnection(connection: CollectionConnection)

    fun insertObject(uuid: UUID, insertObjectQuery: InsertObjectQuery)
}
