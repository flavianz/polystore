package ch.flavianz.driver

import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.QueryPath
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(instruction: CreateCollectionInstruction)
    fun createConnection(connection: ConnectionModel)

    fun insertObject(uuid: UUID, instruction: InsertObjectInstruction)
    fun updateObject(instruction: UpdateObjectInstruction)
    fun insertConnection(
        connection: ConnectionModel,
        collectionRef1: CollectionRef, uuid1: UUID,
        collectionRef2: CollectionRef, uuid2: UUID,
        connectionData: PolyData
    )

    fun take(path: QueryPath, terminal: PolyTerminal.Take): PolyResult.Documents
    fun count(path: QueryPath, terminal: PolyTerminal.Count): PolyResult.Count
}
