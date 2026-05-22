package ch.flavianz.core

import ch.flavianz.data.PolyDocument
import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.exceptions.CollectionNotFoundException
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.exceptions.ConnectionAlreadyExistsException
import ch.flavianz.exceptions.ObjectSchemaMismatch
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.ObjectSchema
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionPath
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.security.InvalidParameterException
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

    fun createCollection(createCollectionInstruction: CreateCollectionInstruction) {
        if(!existsCollection(createCollectionInstruction.parentCollection)) {
            // parent collection does not exist
            throw CollectionNotFoundException(createCollectionInstruction.parentCollection)
        }
        if(existsCollection(createCollectionInstruction.parentCollection.sub(createCollectionInstruction.collectionModel.name))) {
            // collection to be created already exists
            throw CollectionAlreadyExistsException(createCollectionInstruction.parentCollection.sub(createCollectionInstruction.collectionModel.name))
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(createCollectionInstruction) }

        registerCollection(createCollectionInstruction.collectionModel, createCollectionInstruction.parentCollection)
    }

    fun createConnection(connection: ConnectionModel){
        if(connections.containsKey(connection.name)) {
            throw ConnectionAlreadyExistsException(connection.name)
        }
        if(!existsCollection(connection.collection1)) {
            throw CollectionNotFoundException(connection.collection1)
        }
        if(!existsCollection(connection.collection2)) {
            throw CollectionNotFoundException(connection.collection2)
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createConnection)(connection) }

        registerConnection(connection)
    }

    fun registerCollection(collectionModel: CollectionModel, parentCollectionRef: CollectionRef? = null) {
        if(parentCollectionRef != null) {
            check(existsCollection(parentCollectionRef)) { "Parent Collection $parentCollectionRef does not exist" }
        }
        val newCollectionRef = parentCollectionRef?.sub(collectionModel.name) ?: CollectionRef(collectionModel.name)
        check(!existsCollection(newCollectionRef)) { "Collection ${collectionModel.name} already exists" }
        collections[newCollectionRef] = collectionModel
    }

    fun registerConnection(connection: ConnectionModel) {
        connections[connection.name] = connection
    }

    fun insertObject(insertObjectInstruction: InsertObjectInstruction) {
        val collectionRef = insertObjectInstruction.collectionPath.toCollectionRef()

        if(!existsCollection(collectionRef)) {
            throw CollectionNotFoundException(collectionRef)
        }

        val schema = getCollectionModel(collectionRef).schema
        if(!dataMatchesSchema(insertObjectInstruction.data, schema)) {
            throw ObjectSchemaMismatch(insertObjectInstruction.data, schema)
        }

        val objectUuid = UUID.randomUUID()

        DriverManager.getInstance().execute { (DatabaseDriver::insertObject)(objectUuid, insertObjectInstruction) }
    }

    fun updateObject(updateObjectInstruction: UpdateObjectInstruction) {
        val collectionRef = updateObjectInstruction.documentPath.parentCollection().toCollectionRef()

        if(!existsCollection(collectionRef)) {
            throw CollectionNotFoundException(collectionRef)
        }
        val schema = getCollectionModel(collectionRef).schema
        if(!schemaContainsFields(updateObjectInstruction.data, schema)) {
            throw ObjectSchemaMismatch(updateObjectInstruction.data, schema)
        }

        DriverManager.getInstance().execute { (DatabaseDriver::updateObject)(updateObjectInstruction) }
    }

    fun query(query: PolyQuery): PolyResult {

        // validate the path against the schema registry
        for (segment in query.path.segments) {
            if(segment is QuerySegment.Connection) {
                val connectionModel = connections[segment.name] ?: throw IllegalStateException("Connection ${segment.name} not found")
                if(connectionModel.collection1 != )
            }
            val model = currentCollections[segment.collection]
                ?: throw CollectionNotFoundException(CollectionRef(segment.collection))

            // validate condition fields exist in schema
            segment.condition?.let { condition ->
                validateConditionFields(condition, model.schema)
            }

            currentCollections = model.subCollections
        }

        return when (val terminal = query.terminal) {
            is PolyTerminal.Take  -> DriverManager.getInstance().take(query, terminal)
            is PolyTerminal.Count -> DriverManager.getInstance().count(query, terminal)
        }
    }

    fun existsCollection(collectionRef: CollectionRef): Boolean {
        var currentCollections = rootCollections
        for(collectionName in collectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: return false).subCollections
        }
        return true
    }

    fun getCollectionModel(collection: CollectionRef): CollectionModel {
        if(collection.isRoot()) {
            throw InvalidParameterException("Cannot get Collection Model of Root")
        }
        val pathIterator = collection.path.iterator()
        var currentCollectionModel = rootCollections[pathIterator.next()] ?: throw CollectionNotFoundException(collection)
        for(collectionName in pathIterator) {
            currentCollectionModel = (currentCollectionModel.subCollections[collectionName] ?: throw CollectionNotFoundException(collection))
        }
        return currentCollectionModel
    }

    private fun dataMatchesSchema(polyDocument: PolyDocument, schema: ObjectSchema): Boolean {
        for(entry in schema.fields) {
            if(!(polyDocument.fields[entry.key] ?: return false).isType(entry.value)) {
                return false
            }
        }
        return polyDocument.fields.size == schema.fields.size
    }

    private fun schemaContainsFields(polyDocument: PolyDocument, schema: ObjectSchema): Boolean {
        for(entry in polyDocument.fields) {
            if(!entry.value.isType(schema.fields[entry.key] ?: return false)) {
                return false
            }
        }
        return true
    }

    private fun validateConditionFields(condition: Condition, schema: ObjectSchema) {
        when (condition) {
            is Condition.Equals      -> require(schema.fields.containsKey(condition.field)) { "Unknown field: ${condition.field}" }
            is Condition.GreaterThan -> require(schema.fields.containsKey(condition.field)) { "Unknown field: ${condition.field}" }
            is Condition.LessThan    -> require(schema.fields.containsKey(condition.field)) { "Unknown field: ${condition.field}" }
            is Condition.And -> { validateConditionFields(condition.left, schema); validateConditionFields(condition.right, schema) }
            is Condition.Or  -> { validateConditionFields(condition.left, schema); validateConditionFields(condition.right, schema) }
            is Condition.Not -> validateConditionFields(condition.condition, schema)
        }
    }
}