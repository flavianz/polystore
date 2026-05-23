package ch.flavianz.driver

import ch.flavianz.connection.MongoConnection
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
import org.bson.Document
import java.util.UUID

class MongoDriver(val mongoDatabase: MongoDatabase) : DatabaseDriver {
    override fun createCollection(instruction: CreateCollectionInstruction) {
        val collectionModel = instruction.collectionModel
        val collectionName = if (instruction.parentCollection == null)
            CollectionRef(collectionModel.name).toPostgresPath()
        else instruction.parentCollection.sub(collectionModel.name).toPostgresPath()

        mongoDatabase.createCollection(collectionName)
    }

    override fun createConnection(connection: ConnectionModel) {
        TODO("Not yet implemented")
    }

    override fun insertObject(uuid: UUID, instruction: InsertObjectInstruction) {
        val collectionRef = instruction.collectionPath.toCollectionRef()

        val document = Document().append("ps_id", uuid)

        for (field in instruction.data.fields) {
            document.append("ps_f_${field.key}", prepareValue(field.value))
        }


        mongoDatabase.getCollection(collectionRef.toPostgresPath()).insertOne(document)
    }

    override fun updateObject(instruction: UpdateObjectInstruction) {
        TODO("Not yet implemented")
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