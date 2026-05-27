package ch.flavianz.driver

import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
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
        if (query.path.segments.size == 1) {
            val segment = query.path.segments[0] as QuerySegment.Collection
            val mongoCollection = mongoDatabase.getCollection(segment.name)
            val result = if (segment.condition == null) mongoCollection.find() else
                mongoCollection.find(conditionToFilter(segment.condition))

            return parseDocuments(result.toList(), terminal.fields)
        }
        if (query.path.segments.size == 2) {
            val parentSegment = query.path.segments[0] as QuerySegment.Collection
            val subSegment = query.path.segments[1] as QuerySegment.Collection
            lateinit var subDocs: List<Document>
            if(parentSegment.condition == null) {
                val mongoSubCollection = mongoDatabase.getCollection(subSegment.name)
                subDocs = if(subSegment.condition == null) mongoSubCollection.find().toList()
                        else mongoSubCollection.find(conditionToFilter(subSegment.condition)).toList()
            } else {
                val mongoParentCollection = mongoDatabase.getCollection(parentSegment.name)
                val parentDocs = if(subSegment.condition == null)
                    mongoParentCollection.find(
                        conditionToFilter(parentSegment.condition))
                    else mongoParentCollection.find(
                    Filters.and(
                        conditionToFilter(parentSegment.condition),
                        Filters.elemMatch(subSegment.name,
                            conditionToFilter(subSegment.condition))))
                val allSubDocs = parentDocs.map { doc ->
                    (doc[subSegment.name] as? List<*>)?.filterIsInstance<Document>() as List<Document>
                }.flatten()
                subDocs = if(subSegment.condition == null) allSubDocs
                        else allSubDocs.filter { doc -> checkCondition(doc, subSegment.condition) }
            }

            return parseDocuments(subDocs, terminal.fields)
        }
        return PolyResult.Documents(listOf())
    }

    private fun parseDocuments(documents: List<Document>, fields: List<FieldRef>): PolyResult.Documents {
        return PolyResult.Documents(documents.map { doc ->
            buildMap {
                for (field in fields) {
                    when (field) {
                        is FieldRef.Named -> put(
                            "${field.segment}.${field.field}",
                            parsePolyValue(doc["ps_f_${field.field}"])
                        )

                        is FieldRef.Wildcard -> doc.entries.filter { it.key.startsWith("ps_f_") }
                            .forEach {
                                put(
                                    "${field.segment}.${it.key.substring(5)}", parsePolyValue(it.value)
                                )
                            }
                    }

                }
            }
        }.toList())
    }

    private fun parsePolyValue(value: Any?): PolyValue {
        return when (value) {
            is Int -> PolyValue.of(value)
            is String -> PolyValue.of(value)
            is UUID -> PolyValue.of(value)
            null -> PolyValue.NullValue
            else -> throw IllegalStateException("unknown return type")
        }
    }

    private fun conditionToFilter(condition: Condition): Bson {
        return when (condition) {
            is Condition.Comparison -> Filters.eq(condition.field, prepareValue(condition.value))
            is Condition.Logic -> Filters.and(conditionToFilter(condition.left), conditionToFilter(condition.right))
            is Condition.Not -> Filters.not(conditionToFilter(condition.condition))
        }
    }

    private fun checkCondition(document: Document, condition: Condition): Boolean {
        return when (condition) {
            is Condition.Comparison.Equals -> document["ps_f_${condition.field}"] == condition.value
            is Condition.Comparison -> {
                when (val compValue = document["ps_f_${condition.field}"]) {
                    is Number -> if (condition is Condition.Comparison.LessThan)
                        (compValue.toDouble() < condition.value.getIntValue())
                    else (compValue.toDouble() > condition.value.getIntValue())

                    else -> throw IllegalStateException("can't compare a number to value of type ${compValue?.javaClass ?: "null"}")
                }

            }

            is Condition.Logic.And -> checkCondition(document, condition.left) && checkCondition(
                document,
                condition.right
            )

            is Condition.Logic.Or -> checkCondition(document, condition.left) || checkCondition(
                document,
                condition.right
            )

            is Condition.Not -> !checkCondition(document, condition.condition)
        }
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