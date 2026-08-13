package core

import model.PolyData
import driver.DriverManager
import model.CollectionModel
import driver.DatabaseDriver
import model.ConnectionModel
import model.DataType
import model.DatabaseSchema
import model.PolySchema
import query.QueryPath
import query.QuerySegment
import query.Condition
import query.GetQuery
import ch.flavianz.query.GetQueryBuilder
import query.PolyQueryDuration
import query.PolyQueryResult
import query.PolyResultData
import java.util.UUID
import kotlin.collections.iterator
import kotlin.time.Duration.Companion.nanoseconds

object DatabaseManager {
    private var collections = mutableMapOf<String, CollectionModel>()
    private var connections = mutableMapOf<String, ConnectionModel>()

    fun dropAllConnections() {
        for (connection in connections) {
            dropConnection(connection.key)
        }
    }

    fun dropAllCollections() {
        while (collections.isNotEmpty()) {
            dropCollection(collections.keys.first(), true)
        }
    }

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

    fun dropCollection(collectionName: String, recursive: Boolean = false) {
        val collectionModel = getCollectionModel(collectionName)
        if (collectionModel.childCollections.isNotEmpty() && !recursive) {
            throw IllegalArgumentException("collection $collectionName cannot be dropped as it has child collections")
        }
        val connectedCollections =
            connections.values.filter { it.collection1Name == collectionName || it.collection2Name == collectionName }
        require(connectedCollections.isEmpty()) { "collection $collectionName cannot be dropped as it is connected to collections: ${connectedCollections.joinToString()}" }
        DriverManager.execute { (DatabaseDriver::dropCollection)(collectionModel) }

        unregisterCollection(collectionModel)
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

    fun dropConnection(connectionName: String) {
        val connectionModel = getConnectionModel(connectionName)
        DriverManager.execute { (DatabaseDriver::dropConnection)(connectionModel) }

        unregisterConnection(connectionModel)
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

    fun unregisterCollection(collection: CollectionModel, isOriginal: Boolean = true) {
        for (child in collection.childCollections) {
            unregisterCollection(getCollectionModel(child), false)
            collections.remove(child)
        }
        if (isOriginal && collection.hasParentCollection()) {
            getCollectionModel(collection.parentCollection!!).childCollections.remove(collection.name)
        }
        collections.remove(collection.name)
    }

    fun unregisterConnection(connection: ConnectionModel) {
        connections.remove(connection.name)
    }

    fun registerConnection(connection: ConnectionModel) {
        connections[connection.name] = connection
    }

    fun insertDocument(collectionName: String, data: PolyData, parentDocUuid: UUID? = null): UUID {
        check(existsCollection(collectionName)) { "collection $collectionName does not exist" }
        val collectionModel = getCollectionModel(collectionName)
        check(dataContainsAllRequiredFields(data, collectionModel.schema))
        { "document data does not contain all required fields of collection $collectionName" }
        checkIsDataValid(data)

        if (collectionModel.hasParentCollection()) {
            check(parentDocUuid != null) { "collection $collectionName has a parent collection, specify a parent document" }
        } else {
            check(parentDocUuid == null) { "collection $collectionName does not have a parent collection" }
        }

        val objectUuid = UUID.randomUUID()

        DriverManager.execute { (DatabaseDriver::insertDocument)(collectionModel, objectUuid, data, parentDocUuid) }
        return objectUuid
    }

    fun updateDocument(collectionName: String, uuid: UUID, data: PolyData) {
        check(existsCollection(collectionName)) { "collection $collectionName does not exist" }
        val schema = getCollectionModel(collectionName).schema
        check(schemaContainsFields(data, schema))
        { "update data does not match schema of collection $collectionName" }

        DriverManager.execute { (DatabaseDriver::updateDocument)(collectionName, uuid, data) }
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
            dataContainsAllRequiredFields(
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

    fun get(block: GetQueryBuilder.() -> Unit): PolyQueryResult {
        return get(GetQueryBuilder().apply(block).build())
    }

    fun get(query: GetQuery): PolyQueryResult {
        require(query.path.isNotEmpty()) { "query path cannot be empty" }
        require(query.path[0] is QuerySegment.Collection) { "query path must start with a collection" }

        var currentCollectionName: String? = null
        for (segment in query.path) {
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

        val startTime = System.nanoTime()
        val segments = mutableListOf<QuerySegment>()
        for (i in query.path.indices) {
            val segment = query.path[i]
            var hasConnectionBeenReplaced = false
            val isRelevant = when (segment) {
                is QuerySegment.Collection -> segment.only?.isNotEmpty() ?: true || segment.condition != null
                is QuerySegment.Connection -> {
                    if (segment.connectionOnly?.isNotEmpty() ?: true || segment.connectionCondition != null) {
                        segments.add(query.path[i - 1])
                        true
                    } else if (segment.collectionOnly?.isNotEmpty() ?: true || segment.collectionCondition != null) {
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
                query.path.subList(
                    if (hasConnectionBeenReplaced) i + 1 else i,
                    query.path.size
                )
            )
            break
        }

        val queryResult = DriverManager.get(GetQuery(QueryPath(segments), query.limit))

        val elapsedTime = (System.nanoTime() - startTime).nanoseconds

        return PolyQueryResult(
            PolyResultData.Documents(queryResult.data),
            PolyQueryDuration(
                queryResult.duration.queryBuildingDuration,
                queryResult.duration.queryExecutionDuration,
                elapsedTime.minus(queryResult.duration.queryExecutionDuration.plus(queryResult.duration.queryBuildingDuration))
            ),
            queryResult.executionEnvironment
        )
    }

    fun existsCollection(collectionName: String): Boolean {
        return collections.containsKey(collectionName)
    }

    fun getConnectionOrNull(collectionName: String): ConnectionModel? {
        return connections.values.firstOrNull { it.collection1Name == collectionName || it.collection2Name == collectionName }
    }

    fun getCollectionModel(collectionName: String): CollectionModel {
        return collections[collectionName] ?: throw IllegalStateException("collection $collectionName does not exist")
    }

    fun getConnectionModel(connectionName: String): ConnectionModel {
        return connections[connectionName] ?: throw IllegalStateException("connection $connectionName does not exist")
    }

    private fun dataContainsAllRequiredFields(data: PolyData, schema: PolySchema): Boolean {
        for (entry in schema) {
            if (!(entry.value.matchesType(data[entry.key] ?: return false))) {
                return false
            }
        }
        return true
    }

    private fun checkIsDataValid(data: PolyData) {
        if (data.keys.firstOrNull { it.startsWith("_") } != null) {
            throw IllegalArgumentException("data keys cannot start with an underscore")
        }
        if (data.keys.firstOrNull { it.contains(".") } != null) {
            throw IllegalArgumentException("data keys cannot contain a dot")
        }
        for (entry in data) {
            when (entry.value) {
                is String, is Int, is Float, is Boolean, is UUID, null -> continue
                else -> throw IllegalArgumentException("data type ${entry.value?.javaClass?.name} is not allowed")
            }
        }
    }

    private fun schemaContainsFields(polyDocument: PolyData, schema: PolySchema): Boolean {
        for (entry in polyDocument) {
            if (!(schema[entry.key] ?: return false).matchesType(entry.value)) {
                return false
            }
        }
        return true
    }

    private fun validateConditionFields(condition: Condition, schema: PolySchema, allowIdField: Boolean) {
        when (condition) {
            is Condition.Comparison.Equals, is Condition.Comparison.GreaterThan, is Condition.Comparison.LessThan -> {
                if (allowIdField && condition.field == "_id" && condition.value is UUID) {
                    return
                }
                val fieldType = schema[condition.field] ?: return /*filter on dynamic data*/
                check(fieldType.matchesType(condition.value)) { "condition value ${condition.value} does not match field type $fieldType" }
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
                if (allowIdField && condition.field == "_id" &&
                    condition.list.filterIsInstance<UUID>().size == condition.list.size /*only allow filter on uuid*/) {
                    return
                }

                val fieldType = schema[condition.field] ?: return
                require(fieldType != DataType.NULL) { "cannot check for array membership of null field" }
                require(condition.list.filter {
                    fieldType.toType().isInstance(it)
                }.size == condition.list.size) { "Unknown field: ${condition.field}" }
            }
        }
    }

    fun addChildCollections(collections: List<CollectionModel>) {
        for (collection in collections) {
            if (collection.parentCollection != null) {
                val parentCollection = collections.firstOrNull { it.name == collection.parentCollection }
                checkNotNull(parentCollection) { "Parent collection ${collection.parentCollection} not found" }
                parentCollection.childCollections.add(collection.name)
            }
        }
    }
}