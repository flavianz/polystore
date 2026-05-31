package ch.flavianz.core

import ch.flavianz.data.PolyData
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionModel
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.PolySchema
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.util.UUID
import kotlin.collections.iterator

object DatabaseManager {
    private var collections = mutableMapOf<CollectionRef, CollectionModel>()
    private var connections = mutableMapOf<String, ConnectionModel>()

    fun initCollections(collections: MutableMap<CollectionRef, CollectionModel>) {
        this.collections = collections
    }

    fun initConnections(connections: MutableMap<String, ConnectionModel>) {
        this.connections = connections
    }

    fun createCollection(instruction: CreateCollectionInstruction) {
        if (instruction.parentCollection != null) {
            check(existsCollection(instruction.parentCollection))
            { "parent collection ${instruction.parentCollection} does not exist" }
            check(!existsCollection(instruction.parentCollection.sub(instruction.collectionModel.name)))
            { "collection ${instruction.parentCollection.sub(instruction.collectionModel.name)} already exists" }
        } else {
            check(!existsCollection(CollectionRef(instruction.collectionModel.name)))
            { " collection ${instruction.collectionModel.name} already exists" }
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(instruction) }

        registerCollection(instruction.collectionModel, instruction.parentCollection)
    }

    fun createConnection(connection: ConnectionModel) {
        check(!connections.containsKey(connection.name)) { "connection ${connection.name} already exists" }
        check(existsCollection(connection.collection1))
        { "connection collection ${connection.collection1} does not exist" }
        check(existsCollection(connection.collection2))
        { "connection collection ${connection.collection2} does not exist" }

        DriverManager.getInstance().execute { (DatabaseDriver::createConnection)(connection) }

        registerConnection(connection)
    }

    fun registerCollection(collectionModel: CollectionModel, parentCollectionRef: CollectionRef? = null) {
        if (parentCollectionRef != null) {
            check(existsCollection(parentCollectionRef)) { "Parent Collection $parentCollectionRef does not exist" }
        }
        val newCollectionRef = parentCollectionRef?.sub(collectionModel.name) ?: CollectionRef(collectionModel.name)
        check(!existsCollection(newCollectionRef)) { "Collection ${collectionModel.name} already exists" }
        collections[newCollectionRef] = collectionModel
    }

    fun registerConnection(connection: ConnectionModel) {
        connections[connection.name] = connection
    }

    fun insertDocument(insertObjectInstruction: InsertObjectInstruction): UUID {
        val collectionRef = insertObjectInstruction.collectionPath.toCollectionRef()

        check(existsCollection(collectionRef)) { "collection $collectionRef does not exist" }
        val schema = getCollectionModel(collectionRef).schema
        check(dataMatchesSchema(insertObjectInstruction.data, schema))
        { "insertion data does not match schema of collection $collectionRef" }

        val objectUuid = UUID.randomUUID()

        DriverManager.getInstance().execute { (DatabaseDriver::insertDocument)(objectUuid, insertObjectInstruction) }
        return objectUuid
    }

    fun updateObject(updateObjectInstruction: UpdateObjectInstruction) {
        val collectionRef = updateObjectInstruction.documentPath.parentCollection().toCollectionRef()

        check(existsCollection(collectionRef)) { "collection $collectionRef does not exist" }
        val schema = getCollectionModel(collectionRef).schema
        check(schemaContainsFields(updateObjectInstruction.data, schema))
        { "update data does not match schema of collection $collectionRef" }

        DriverManager.getInstance().execute { (DatabaseDriver::updateDocument)(updateObjectInstruction) }
    }

    fun insertConnection(
        connectionName: String,
        collectionRef1: CollectionRef, uuid1: UUID,
        collectionRef2: CollectionRef, uuid2: UUID,
        connectionData: PolyData
    ) {
        val connection = connections[connectionName]
        checkNotNull(connection) { "connection $connectionName does not exist" }

        check(
            (collectionRef1 == connection.collection1 && collectionRef2 == connection.collection2)
                    || (collectionRef1 == connection.collection2 && collectionRef2 == connection.collection1)
        )
        { "collections to not match collections stored in connection" }
        check(
            dataMatchesSchema(
                connectionData,
                connection.connectionDataSchema
            )
        ) { "connection data does not match schema" }
        DriverManager.getInstance().execute {
            (DatabaseDriver::insertConnection)(
                connection,
                if (collectionRef1 == connection.collection1) collectionRef1 else collectionRef2,
                if (collectionRef1 == connection.collection1) uuid1 else uuid2,
                if (collectionRef1 == connection.collection1) collectionRef2 else collectionRef1,
                if (collectionRef1 == connection.collection1) uuid2 else uuid1,
                connectionData
            )
        }

    }

    fun query(query: PolyQuery): PolyResult {
        require(query.path.segments.isNotEmpty()) { "query path cannot be empty" }
        require(query.path.segments[0] is QuerySegment.Collection) { "query path must start with a collection" }
        val segmentIterator = query.path.segments.iterator()
        val firstSegment = segmentIterator.next() as QuerySegment.Collection
        //var currentPath = CollectionRef((query.path.segments[0] as QuerySegment.Collection).name)
        /*val firstCollectionModel = getCollectionModel(currentPath)
        firstSegment.condition?.let { condition ->
            validateConditionFields(condition, firstCollectionModel.schema)
        }*/
        // validate the path against the schema registry
        var currentPath: CollectionRef? = null
        for (segment in query.path.segments) {
            when (segment) {
                is QuerySegment.Connection -> {
                    assert(currentPath != null)
                    val connectionModel = getConnectionModel(segment.connectionName)
                    check(
                        connectionModel.collection1 == currentPath
                                || connectionModel.collection2 == currentPath
                    )
                    { "connection ${segment.connectionName} does not exist on collection $currentPath" }

                    segment.connectionCondition?.let { condition ->
                        validateConditionFields(condition, connectionModel.connectionDataSchema)
                    }

                    currentPath = if (connectionModel.collection1 == currentPath) connectionModel.collection2
                    else connectionModel.collection1

                    segment.collectionCondition?.let { condition ->
                        validateConditionFields(condition, getCollectionModel(currentPath).schema)
                    }
                }

                is QuerySegment.Collection -> {
                    currentPath = currentPath?.sub(segment.name) ?: CollectionRef(segment.name)
                    val collectionModel = getCollectionModel(currentPath)
                    segment.condition?.let { condition ->
                        validateConditionFields(condition, collectionModel.schema)
                    }
                }
            }
        }

        return when (val terminal = query.terminal) {
            is PolyTerminal.Take -> DriverManager.getInstance().take(query, terminal)
            is PolyTerminal.Count -> DriverManager.getInstance().count(query, terminal)
        }
    }

    fun existsCollection(collectionRef: CollectionRef): Boolean {
        return collections[collectionRef] != null
    }

    fun getConnectionOrNull(collectionRef: CollectionRef): ConnectionModel? {
        return connections.values.firstOrNull { it.collection1 == collectionRef || it.collection2 == collectionRef }
    }

    fun getCollectionModel(collectionRef: CollectionRef): CollectionModel {
        return collections[collectionRef] ?: throw IllegalStateException("collection $collectionRef does not exist")
    }

    fun getConnectionModel(connectionName: String): ConnectionModel {
        return connections[connectionName] ?: throw IllegalStateException("connection $connectionName does not exist")
    }

    fun getCollectionRef(queryPath: QueryPath): CollectionRef {
        val segmentIterator = queryPath.segments.iterator()
        var currentPath = CollectionRef(
            when (val it = segmentIterator.next()) {
                is QuerySegment.Collection -> it.name
                is QuerySegment.Connection -> it.connectionName
            }
        )

        for (segment in segmentIterator) {
            when (segment) {
                is QuerySegment.Collection -> {
                    currentPath = currentPath.sub(segment.name)
                }

                is QuerySegment.Connection -> {
                    val connectionModel = getConnectionModel(segment.connectionName)
                    check(connectionModel.collection1 == currentPath || connectionModel.collection2 == currentPath) { "connection ${connectionModel.name} is not connected to collection ${segment.connectionName}" }
                    currentPath =
                        if (connectionModel.collection1 == currentPath) connectionModel.collection2 else connectionModel.collection1
                }
            }
        }

        return currentPath
    }

    private fun dataMatchesSchema(polyDocument: PolyData, schema: PolySchema): Boolean {
        for (entry in schema) {
            if (!(polyDocument[entry.key] ?: return false).isType(entry.value)) {
                return false
            }
        }
        return polyDocument.size == schema.size
    }

    private fun schemaContainsFields(polyDocument: PolyData, schema: PolySchema): Boolean {
        for (entry in polyDocument) {
            if (!entry.value.isType(schema[entry.key] ?: return false)) {
                return false
            }
        }
        return true
    }

    private fun validateConditionFields(condition: Condition, schema: PolySchema) {
        when (condition) {
            is Condition.Comparison.Equals, is Condition.Comparison.GreaterThan, is Condition.Comparison.LessThan -> {
                val fieldType = schema[condition.field]
                require(fieldType != null) { "Unknown field: ${condition.field}" }
                check(condition.value.isType(fieldType)) { "condition value ${condition.value} does not match field type $fieldType" }
            }

            is Condition.Logic.And, is Condition.Logic.Or -> {
                validateConditionFields(condition.left, schema); validateConditionFields(condition.right, schema)
            }

            is Condition.Not -> validateConditionFields(condition.condition, schema)
        }
    }
}