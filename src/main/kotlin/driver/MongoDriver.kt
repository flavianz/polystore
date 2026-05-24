package ch.flavianz.driver

import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.ConnectionModel
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.Document
import java.util.UUID

class MongoDriver(val mongoDatabase: MongoDatabase) : DatabaseDriver {
    override fun createCollection(instruction: CreateCollectionInstruction) {
        val collectionModel = instruction.collectionModel

        val collectionName = if (instruction.parentCollection == null)
            CollectionRef(collectionModel.name).toPostgresPath()
        else instruction.parentCollection.sub(collectionModel.name).toPostgresPath()

        //TODO: change name to hashName (probably)
        mongoDatabase.createCollection(collectionName)
    }

    override fun createConnection(connection: ConnectionModel) {
        TODO("Not yet implemented")
    }

    override fun insertObject(uuid: UUID, instruction: InsertObjectInstruction) {
        val document = Document().append("_id", uuid)
        for (field in instruction.data.fields) {
            document.append("ps_f_${field.key}", prepareValue(field.value))
        }

        val collectionRef = instruction.collectionPath.toCollectionRef()
        if (instruction.collectionPath.hasParentDoc()) {
            val parentCollection = instruction.collectionPath.parentDoc().parentCollection()
            val parentCollectionName = parentCollection.toCollectionRef().toPostgresPath()
            val mongoParentCollection = mongoDatabase.getCollection(parentCollectionName)
            mongoParentCollection.updateOne(
                Filters.eq("_id", instruction.collectionPath.parentDoc().uuid),
                Updates.push(collectionRef.leafName(), document)
            )
        }

        mongoDatabase.getCollection(collectionRef.toPostgresPath()).insertOne(document)
    }

    override fun updateObject(instruction: UpdateObjectInstruction) {
        if (instruction.documentPath.parentCollection().hasParentDoc()) {
            // update parent collection
            val parentCollection =
                instruction.documentPath.parentCollection().parentDoc().parentCollection().toCollectionRef()
            val collectionName = instruction.documentPath.parentCollection().toCollectionRef().leafName()
            val mongoCollection = mongoDatabase.getCollection(parentCollection.toPostgresPath())

            mongoCollection.updateOne(
                Filters.eq("${collectionName}._id", instruction.documentPath.uuid),
                Updates.combine(
                    instruction.data.fields.map { Updates.set("$collectionName.$.${it.key}", prepareValue(it.value)) }
                )
            )
        }
        val mongoCollection =
            mongoDatabase.getCollection(instruction.documentPath.parentCollection().toCollectionRef().toPostgresPath())
        mongoCollection.updateOne(
            Filters.eq("_id", instruction.documentPath.uuid),
            Updates.combine(
                instruction.data.fields.map { Updates.set(it.key, prepareValue(it.value)) }
            )
        )
    }

    override fun take(
        query: PolyQuery,
        terminal: PolyTerminal.Take
    ): PolyResult.Documents {
        TODO("Not yet implemented")
    }

    override fun count(
        query: PolyQuery,
        terminal: PolyTerminal.Count
    ): PolyResult.Count {
        TODO("Not yet implemented")
    }


    private fun prepareValue(value: PolyValue): Any? {
        return value.value
    }
}