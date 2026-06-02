package ch.flavianz.driver

import ch.flavianz.data.PolyData
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.QueryPath
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(instruction: CreateCollectionInstruction)
    fun createConnection(connection: ConnectionModel)

    fun insertDocument(uuid: UUID, instruction: InsertObjectInstruction)
    fun updateDocument(instruction: UpdateObjectInstruction)
    fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String, uuid1: UUID,
        collection2Name: String, uuid2: UUID,
        connectionData: PolyData
    )

    fun take(path: QueryPath, terminal: PolyTerminal.Take): PolyResult.Documents
    fun count(path: QueryPath, terminal: PolyTerminal.Count): PolyResult.Count
}
