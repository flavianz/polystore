package ch.flavianz.driver

import ch.flavianz.model.PolyData
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.query.GetQuery
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

    fun get(query: GetQuery): TimedDriverResult<List<PolyData>>
    //fun count(path: GetQuery, terminal: PolyTerminal.Count): PolyResultData.Count

    fun init()
    fun getDatabaseSchema(): DatabaseSchema
}