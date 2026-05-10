package ch.flavianz.driver

import ch.flavianz.data.DataObject
import ch.flavianz.model.CollectionConnection
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.query.Query
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(createCollectionInstruction: CreateCollectionInstruction)
    fun createConnection(connection: CollectionConnection)

    fun insertObject(uuid: UUID, insertObjectInstruction: InsertObjectInstruction)
    fun updateObject(updateObjectInstruction: UpdateObjectInstruction)

    fun query(query: Query): List<DataObject>
}
