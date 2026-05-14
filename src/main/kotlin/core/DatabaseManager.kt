package ch.flavianz.core

import ch.flavianz.data.CollectionRef
import ch.flavianz.data.PolyDocument
import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.exceptions.CollectionNotFoundException
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.exceptions.ConnectionAlreadyExistsException
import ch.flavianz.exceptions.ObjectSchemaMismatch
import ch.flavianz.model.CollectionConnection
import ch.flavianz.model.ObjectSchema
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.query.PolyQuery
import java.security.InvalidParameterException
import java.util.UUID
import kotlin.collections.iterator

object DatabaseManager {
    private var rootCollections = mutableMapOf<String, CollectionModel>()
    private var connections = mutableMapOf<String, CollectionConnection>()

    fun initRootCollections(rootCollections: MutableMap<String, CollectionModel>) {
        this.rootCollections = rootCollections
    }

    fun initConnections(connections: MutableMap<String, CollectionConnection>) {
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
    }

    fun createConnection(connection: CollectionConnection){
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

    fun registerCollection(collectionModel: CollectionModel, parentCollectionRef: CollectionRef) {
        var currentCollections = rootCollections

        for(collectionName in parentCollectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: throw CollectionNotFoundException(parentCollectionRef)).subCollections
        }
        currentCollections[collectionModel.name] = collectionModel
    }

    fun registerConnection(connection: CollectionConnection) {
        connections[connection.name] = connection
    }

    fun insertObject(insertObjectInstruction: InsertObjectInstruction) {
        val collectionRef = insertObjectInstruction.collectionPathRef.toCollectionRef()

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
        val collectionRef = updateObjectInstruction.documentPathRef.parentCollection().toCollectionRef()

        if(!existsCollection(collectionRef)) {
            throw CollectionNotFoundException(collectionRef)
        }
        val schema = getCollectionModel(collectionRef).schema
        if(!schemaContainsFields(updateObjectInstruction.data, schema)) {
            throw ObjectSchemaMismatch(updateObjectInstruction.data, schema)
        }

        DriverManager.getInstance().execute { (DatabaseDriver::updateObject)(updateObjectInstruction) }
    }

    fun query(query: PolyQuery) {
        /*var currentCollections = rootCollections
        for(selector in query.selectors.iterator()) {
            val currentModel = currentCollections[selector.collectionName] ?:
            throw CollectionNotFoundException(CollectionRef(LinkedList(query.selectors.map { it.collectionName })))

            for(filter in selector.filters) {
                val fieldDataType = currentModel.schema.fields[filter.propertyName] ?: throw IllegalStateException("field ${filter.propertyName} does not exists")
                if(!filter.operand.isDataTypeApplicable(fieldDataType)) {
                    throw IllegalStateException("Operand ${filter.operand} does not allow type \"$fieldDataType\"")
                }
            }

            currentCollections = currentModel.subCollections
        }*/


    }

    private fun existsCollection(collectionRef: CollectionRef): Boolean {
        var currentCollections = rootCollections
        for(collectionName in collectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: return false).subCollections
        }
        return true
    }

    private fun getCollectionModel(collection: CollectionRef): CollectionModel {
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
}