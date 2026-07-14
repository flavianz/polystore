package ch.flavianz.driver

import ch.flavianz.data.PolyData
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.model.QueryPath
import ch.flavianz.query.PolyDriverQueryDuration
import ch.flavianz.query.PolyResultData
import ch.flavianz.query.PolyTerminal
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String? = null)
    fun dropCollection(collection: CollectionModel)
    fun createConnection(connection: ConnectionModel)
    fun dropConnection(connectionModel: ConnectionModel)

    fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID? = null)
    fun updateDocument(instruction: UpdateObjectInstruction)
    fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String, uuid1: UUID,
        collection2Name: String, uuid2: UUID,
        connectionData: PolyData
    )

    fun take(path: QueryPath, terminal: PolyTerminal.Take): TimedDriverResult<List<PolyData>>
    fun count(path: QueryPath, terminal: PolyTerminal.Count): PolyResultData.Count

    fun init()
    fun getDatabaseSchema(): DatabaseSchema
}

data class TimedDriverResult<T>(
    val data: T,
    val duration: PolyDriverQueryDuration,
    val executedQueries: List<String>
)