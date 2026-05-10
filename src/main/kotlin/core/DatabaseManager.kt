package ch.flavianz.core

import ch.flavianz.data.CollectionRef
import ch.flavianz.data.DataObject
import ch.flavianz.driver.DriverManager
import ch.flavianz.exceptions.CollectionAlreadyExistsException
import ch.flavianz.model.CollectionModel
import ch.flavianz.exceptions.CollectionNotFoundException
import ch.flavianz.query.CreateCollectionQuery
import ch.flavianz.driver.DatabaseDriver
import ch.flavianz.exceptions.ConnectionAlreadyExistsException
import ch.flavianz.exceptions.ObjectSchemaMismatch
import ch.flavianz.model.CollectionConnection
import ch.flavianz.model.ObjectSchema
import ch.flavianz.query.InsertObjectQuery
import ch.flavianz.query.UpdateObjectQuery
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

    fun createCollection(createCollectionQuery: CreateCollectionQuery) {
        if(!existsCollection(createCollectionQuery.parentCollection)) {
            // parent collection does not exist
            throw CollectionNotFoundException(createCollectionQuery.parentCollection)
        }
        if(existsCollection(createCollectionQuery.parentCollection.sub(createCollectionQuery.collectionModel.name))) {
            // collection to be created already exists
            throw CollectionAlreadyExistsException(createCollectionQuery.parentCollection.sub(createCollectionQuery.collectionModel.name))
        }

        DriverManager.getInstance().execute { (DatabaseDriver::createCollection)(createCollectionQuery) }
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

    private fun existsCollection(collectionRef: CollectionRef): Boolean {
        var currentCollections = rootCollections
        for(collectionName in collectionRef.path.iterator()) {
            currentCollections = (currentCollections[collectionName] ?: return false).subCollections
        }
        return true
    }

    fun insertObject(insertObjectQuery: InsertObjectQuery) {
        val collectionRef = insertObjectQuery.collectionPathRef.toCollectionRef()

        if(!existsCollection(collectionRef)) {
            throw CollectionNotFoundException(collectionRef)
        }
        val schema = getCollectionModel(collectionRef).schema
        if(!dataMatchesSchema(insertObjectQuery.data, schema)) {
            throw ObjectSchemaMismatch(insertObjectQuery.data, schema)
        }

        val objectUuid = UUID.randomUUID()

        DriverManager.getInstance().execute { (DatabaseDriver::insertObject)(objectUuid, insertObjectQuery) }
    }

    fun updateObject(updateObjectQuery: UpdateObjectQuery) {
        val collectionRef = updateObjectQuery.documentPathRef.parentCollection().toCollectionRef()

        if(!existsCollection(collectionRef)) {
            throw CollectionNotFoundException(collectionRef)
        }
        val schema = getCollectionModel(collectionRef).schema
        if(!schemaContainsFields(updateObjectQuery.data, schema)) {
            throw ObjectSchemaMismatch(updateObjectQuery.data, schema)
        }

        DriverManager.getInstance().execute { (DatabaseDriver::updateObject)(updateObjectQuery) }
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

    private fun dataMatchesSchema(dataObject: DataObject, schema: ObjectSchema): Boolean {
        for(entry in schema.fields) {
            if(!entry.value.matchesType(dataObject.fields[entry.key])) {
                return false
            }
        }
        return dataObject.fields.size == schema.fields.size
    }

    private fun schemaContainsFields(dataObject: DataObject, schema: ObjectSchema): Boolean {
        for(entry in dataObject.fields) {
            if(!(schema.fields[entry.key]?.matchesType(entry.value) ?: return false)) {
                return false
            }
        }
        return true
    }
}