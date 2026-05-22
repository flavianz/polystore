package ch.flavianz.driver

import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(instruction: CreateCollectionInstruction)
    fun createConnection(connection: ConnectionModel)

    fun insertObject(uuid: UUID, instruction: InsertObjectInstruction)
    fun updateObject(instruction: UpdateObjectInstruction)

    fun take(query: PolyQuery, terminal: PolyTerminal.Take): PolyResult.Documents
    fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResult.Count
}
