package driver

import model.PolyData
import model.ConnectionModel
import model.CollectionModel
import model.DatabaseSchema
import model.PolySchema
import query.GetQuery
import java.util.UUID

interface DatabaseDriver {
    fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String? = null)
    fun dropCollection(collection: CollectionModel)
    fun createConnection(connection: ConnectionModel)
    fun dropConnection(connectionModel: ConnectionModel)

    fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID? = null)
    fun updateDocument(collectionName: String, uuid: UUID, data: PolyData)
    fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String, uuid1: UUID,
        collection2Name: String, uuid2: UUID,
        connectionData: PolyData
    )

    // TODO: update connection

    fun get(query: GetQuery): TimedDriverResult<List<PolyData>>
    //fun count(path: GetQuery, terminal: PolyTerminal.Count): PolyResultData.Count

    fun init()
    fun getDatabaseSchema(): DatabaseSchema
}