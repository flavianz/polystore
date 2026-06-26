package ch.flavianz.core

import ch.flavianz.data.PolyData
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionModel
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
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
    private var collections = mutableMapOf<String, CollectionModel>()
    private var connections = mutableMapOf<String, ConnectionModel>()

    fun initCollections(collections: List<CollectionModel>) {
        this.collections = collections.associateBy { it.name }.toMutableMap()
    }

    fun initConnections(connections: List<ConnectionModel>) {
        this.connections = connections.associateBy { it.name }.toMutableMap()
    }

    fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String? = null) {
        if (parentCollectionName != null) {
            check(existsCollection(parentCollectionName))
            { "parent collection $parentCollectionName does not exist" }
            check(!existsCollection(collectionName))
            { "collection $collectionName already exists" }
        } else {
            check(!existsCollection(collectionName))
            { " collection $collectionName already exists" }
        }

        check(!schema.containsKey("_id")) { "_id is reserved" }

        DriverManager.execute { (DatabaseDriver::createCollection)(collectionName, schema, parentCollectionName) }

        registerCollection(collectionName, schema, parentCollectionName)
    }

    fun createConnection(connection: ConnectionModel) {
        check(!connections.containsKey(connection.name)) { "connection ${connection.name} already exists" }
        check(existsCollection(connection.collection1Name))
        { "connection collection ${connection.collection1Name} does not exist" }
        check(existsCollection(connection.collection2Name))
        { "connection collection ${connection.collection2Name} does not exist" }

        DriverManager.execute { (DatabaseDriver::createConnection)(connection) }

        registerConnection(connection)
    }

    fun registerCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        if (parentCollectionName != null) {
            check(existsCollection(parentCollectionName)) { "Parent Collection $parentCollectionName does not exist" }
        }
        check(!existsCollection(collectionName)) { "Collection $collectionName already exists" }
        collections[collectionName] = CollectionModel(collectionName, schema, mutableListOf(), parentCollectionName)
        if (parentCollectionName != null) {
            getCollectionModel(parentCollectionName).childCollections.add(collectionName)
        }
    }

    fun registerConnection(connection: ConnectionModel) {
        connections[connection.name] = connection
    }

    fun insertDocument(collectionName: String, data: PolyData, parentDocUuid: UUID? = null): UUID {
        check(existsCollection(collectionName)) { "collection $collectionName does not exist" }
        val collectionModel = getCollectionModel(collectionName)
        check(dataMatchesSchema(data, collectionModel.schema))
        { "insertion data does not match schema of collection $collectionName" }

        if (collectionModel.hasParentCollection()) {
            checkNotNull(collectionModel.parentCollection) { "collection $collectionName has a parent collection, specify a parent document" }
        } else {
            check(parentDocUuid == null) { "collection $collectionName does not have a parent collection" }
        }

        val objectUuid = UUID.randomUUID()

        DriverManager.execute { (DatabaseDriver::insertDocument)(collectionModel, objectUuid, data, parentDocUuid) }
        return objectUuid
    }

    fun updateObject(updateObjectInstruction: UpdateObjectInstruction) {
        val collectionRef = updateObjectInstruction.documentPath.parentCollection().toCollectionRef()

        check(existsCollection(collectionRef.leafName())) { "collection $collectionRef does not exist" }
        val schema = getCollectionModel(collectionRef).schema
        check(schemaContainsFields(updateObjectInstruction.data, schema))
        { "update data does not match schema of collection $collectionRef" }

        DriverManager.execute { (DatabaseDriver::updateDocument)(updateObjectInstruction) }
    }

    fun listCollections(): List<CollectionModel> {
        return collections.values.toList()
    }

    fun listConnections(): List<ConnectionModel> {
        return connections.values.toList()
    }

    fun getSchema(): DatabaseSchema {
        return DatabaseSchema(listCollections().toSet(), listConnections().toSet())
    }

    fun insertConnection(
        connectionName: String,
        collection1Name: String, uuid1: UUID,
        collection2Name: String, uuid2: UUID,
        connectionData: PolyData
    ) {
        val connection = connections[connectionName]
        checkNotNull(connection) { "connection $connectionName does not exist" }

        check(
            (collection1Name == connection.collection1Name && collection2Name == connection.collection2Name)
                    || (collection1Name == connection.collection2Name && collection2Name == connection.collection1Name)
        )
        { "collections do not match collections stored in connection" }
        check(
            dataMatchesSchema(
                connectionData,
                connection.connectionDataSchema
            )
        ) { "connection data does not match schema" }
        DriverManager.execute {
            (DatabaseDriver::insertConnection)(
                connection,
                if (collection1Name == connection.collection1Name) collection1Name else collection2Name,
                if (collection1Name == connection.collection1Name) uuid1 else uuid2,
                if (collection1Name == connection.collection1Name) collection2Name else collection1Name,
                if (collection1Name == connection.collection1Name) uuid2 else uuid1,
                connectionData
            )
        }

    }

    fun query(query: PolyQuery): PolyResult {
        require(query.path.segments.isNotEmpty()) { "query path cannot be empty" }
        require(query.path.segments[0] is QuerySegment.Collection) { "query path must start with a collection" }

        var currentCollectionName: String? = null
        for (segment in query.path.segments) {
            when (segment) {
                is QuerySegment.Collection -> {
                    currentCollectionName = segment.name
                    val collectionModel = getCollectionModel(currentCollectionName)
                    if (segment.condition != null) {
                        validateConditionFields(segment.condition, collectionModel.schema, true)
                    }
                }

                is QuerySegment.Connection -> {
                    assert(currentCollectionName != null)
                    val connectionModel = getConnectionModel(segment.connectionName)
                    check(
                        connectionModel.collection1Name == currentCollectionName
                                || connectionModel.collection2Name == currentCollectionName
                    )
                    { "connection ${segment.connectionName} does not exist on collection $currentCollectionName" }

                    segment.connectionCondition?.let { condition ->
                        validateConditionFields(condition, connectionModel.connectionDataSchema, false)
                    }

                    currentCollectionName =
                        if (connectionModel.collection1Name == currentCollectionName) connectionModel.collection2Name
                        else connectionModel.collection1Name

                    segment.collectionCondition?.let { condition ->
                        validateConditionFields(condition, getCollectionModel(currentCollectionName).schema, true)
                    }
                }
            }
        }

        return when (val terminal = query.terminal) {
            is PolyTerminal.Take -> {
                val segments = mutableListOf<QuerySegment>()
                for (i in query.path.segments.indices) {
                    val segment = query.path.segments[i]
                    var hasConnectionBeenReplaced = false
                    val isRelevant = when (segment) {
                        is QuerySegment.Collection -> terminal.fields.any { it.segment == segment.name } || segment.condition != null
                        is QuerySegment.Connection -> {
                            if (terminal.fields.any { it.segment == segment.connectionName } || segment.connectionCondition != null) {
                                segments.add(query.path.segments[i - 1])
                                true
                            } else if (terminal.fields.any { it.segment == segment.collectionName } || segment.collectionCondition != null) {
                                segments.add(
                                    QuerySegment.Collection(
                                        segment.collectionName,
                                        segment.collectionCondition
                                    )
                                )
                                hasConnectionBeenReplaced = true
                                true
                            } else {
                                false
                            }
                        }
                    }
                    if (!isRelevant) {
                        continue
                    }
                    segments.addAll(
                        query.path.segments.subList(
                            if (hasConnectionBeenReplaced) i + 1 else i,
                            query.path.segments.size
                        )
                    )
                    break
                }

                PolyResult.Documents(
                    DriverManager.take(PolyQuery(QueryPath(segments), terminal), terminal)
                )
            }

            is PolyTerminal.Count -> DriverManager.count(query, terminal)
        }
    }

    fun existsCollection(collectionRef: CollectionRef): Boolean {
        val segmentIterator = collectionRef.segments.iterator()
        var currentModel = collections[segmentIterator.next().name] ?: return false
        for (segment in segmentIterator) {
            if (!currentModel.childCollections.contains(segment.name)) {
                return false
            }
            currentModel = collections[segment.name] ?: return false
        }
        return true
    }

    fun existsCollection(collectionName: String): Boolean {
        return collections[collectionName] != null
    }

    fun getConnectionOrNull(collectionName: String): ConnectionModel? {
        return connections.values.firstOrNull { it.collection1Name == collectionName || it.collection2Name == collectionName }
    }

    fun getCollectionModel(collectionRef: CollectionRef): CollectionModel {
        return getCollectionModel(collectionRef.leafName())
    }

    fun getCollectionModel(collectionName: String): CollectionModel {
        return collections[collectionName] ?: throw IllegalStateException("collection $collectionName does not exist")
    }

    fun getConnectionModel(connectionName: String): ConnectionModel {
        return connections[connectionName] ?: throw IllegalStateException("connection $connectionName does not exist")
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

    private fun validateConditionFields(condition: Condition, schema: PolySchema, allowIdField: Boolean) {
        when (condition) {
            is Condition.Comparison.Equals, is Condition.Comparison.GreaterThan, is Condition.Comparison.LessThan -> {
                if (!(allowIdField && condition.field == "_id" && condition.value.isType(DataType.UUID))) {
                    val fieldType = schema[condition.field]
                    require(fieldType != null) { "Unknown field: ${condition.field}" }
                    check(condition.value.isType(fieldType)) { "condition value ${condition.value} does not match field type $fieldType" }
                }
            }

            is Condition.Logic.And, is Condition.Logic.Or -> {
                validateConditionFields(condition.left, schema, allowIdField); validateConditionFields(
                    condition.right,
                    schema,
                    allowIdField
                )
            }

            is Condition.Not -> validateConditionFields(condition.condition, schema, allowIdField)
            is Condition.In -> {
                if (!(allowIdField && condition.field == "_id")) {
                    val fieldType = schema[condition.field]
                    require(fieldType != null) { "Unknown field: ${condition.field}" }
                }
            }
        }
    }
}