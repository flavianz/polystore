package ch.flavianz.driver

import ch.flavianz.data.PolyDocument
import ch.flavianz.model.CollectionConnection
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(createCollectionInstruction: CreateCollectionInstruction)
    fun createConnection(connection: CollectionConnection)

    fun insertObject(uuid: UUID, insertObjectInstruction: InsertObjectInstruction)
    fun updateObject(updateObjectInstruction: UpdateObjectInstruction)

    fun take(query: PolyQuery, terminal: PolyTerminal.Take): PolyResult.Documents
    fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResult.Count
}
