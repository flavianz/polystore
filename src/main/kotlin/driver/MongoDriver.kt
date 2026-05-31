package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
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
        // nothing to do
    }

    override fun insertDocument(uuid: UUID, instruction: InsertObjectInstruction) {
        val document = Document().append("_id", uuid)
        for (field in instruction.data) {
            document.append("ps_f_${field.key}", prepareValue(field.value))
        }

        val collectionRef = instruction.collectionPath.toCollectionRef()
        if (instruction.collectionPath.hasParentDoc()) {
            val parentCollection = instruction.collectionPath.parentDoc().parentCollection()
            val parentCollectionName = parentCollection.toCollectionRef().toPostgresPath()
            val mongoParentCollection = mongoDatabase.getCollection(parentCollectionName)
            mongoParentCollection.updateOne(
                Filters.eq("_id", instruction.collectionPath.parentDoc().uuid),
                Updates.push("ps_sub_${collectionRef.leafName()}", document)
            )
        }

        mongoDatabase.getCollection(collectionRef.toPostgresPath()).insertOne(document)
    }

    override fun updateDocument(instruction: UpdateObjectInstruction) {
        if (instruction.documentPath.parentCollection().hasParentDoc()) {
            // update parent collection
            val parentCollection =
                instruction.documentPath.parentCollection().parentDoc().parentCollection().toCollectionRef()
            val collectionName = "ps_sub_${instruction.documentPath.parentCollection().toCollectionRef().leafName()}"
            val mongoCollection = mongoDatabase.getCollection(parentCollection.toPostgresPath())

            mongoCollection.updateOne(
                Filters.eq("${collectionName}._id", instruction.documentPath.uuid),
                Updates.combine(
                    instruction.data.map {
                        Updates.set(
                            "$collectionName.$.ps_f_${it.key}",
                            prepareValue(it.value)
                        )
                    }
                )
            )
        }
        val mongoCollection =
            mongoDatabase.getCollection(instruction.documentPath.parentCollection().toCollectionRef().toPostgresPath())
        mongoCollection.updateOne(
            Filters.eq("_id", instruction.documentPath.uuid),
            Updates.combine(
                instruction.data.map { Updates.set("ps_f_${it.key}", prepareValue(it.value)) }
            )
        )

        val collectionRef = instruction.documentPath.parentCollection().toCollectionRef()
        val connection = DatabaseManager.getConnectionOrNull(collectionRef)

        if (connection != null) {
            // update connected documents
            val connectedCollection =
                if (connection.collection1 == collectionRef) connection.collection2 else connection.collection1
            val connectionName = "ps_con_${connection.name}"
            val mongoDoc = mongoCollection.find(Filters.eq("_id", instruction.documentPath.uuid)).firstOrNull()
            checkNotNull(mongoDoc) { "updated mongo doc does not exist" }

            val connectedDocs = (mongoDoc[connectionName] as List<*>?)?.filterIsInstance<Document>() ?: emptyList()
            val ids = connectedDocs.map { it["_id"] as UUID }

            val mongoConnectedCollection = mongoDatabase.getCollection(connectedCollection.toPostgresPath())
            mongoConnectedCollection.updateMany(
                Filters.`in`("_id", ids),
                Updates.combine(
                    instruction.data.map {
                        Updates.set(
                            "ps_sub_${collectionRef.leafName()}.$.ps_doc.ps_f_${it.key}",
                            prepareValue(it.value)
                        )
                    })
            )
        }
    }

    override fun insertConnection(
        connection: ConnectionModel,
        collectionRef1: CollectionRef,
        uuid1: UUID,
        collectionRef2: CollectionRef,
        uuid2: UUID,
        connectionData: PolyData
    ) {
        val mongoCollection1 = mongoDatabase.getCollection(collectionRef1.toPostgresPath())
        val mongoCollection2 = mongoDatabase.getCollection(collectionRef2.toPostgresPath())

        val doc1 = mongoCollection1.find(Filters.eq("_id", uuid1)).firstOrNull()
        val doc2 = mongoCollection2.find(Filters.eq("_id", uuid2)).firstOrNull()
        check(doc1 != null && doc2 != null) { "did not find both documents of inserted connection" }

        fun prepareInsertDoc(doc: Document): Map<String, Any> = doc.entries.map {
            // remove data from subcollections and connections, only leave ids
            if (it.key.startsWith("ps_con_") || it.key.startsWith("ps_col_")) {
                val docs = (it.value as List<*>).filterIsInstance<Document>()
                return@map it.key to docs.map { doc -> doc["_id"] }
            }
            return@map it.key to it.value
        }.toMap()

        val insertDoc1 = Document(
            mapOf(
                "ps_rel" to connectionData.map { it.key to prepareValue(it.value) }.toMap(),
                "ps_doc" to prepareInsertDoc(doc1)
            )
        )
        val insertDoc2 = Document(
            mapOf(
                "ps_rel" to connectionData.map { it.key to prepareValue(it.value) }.toMap(),
                "ps_doc" to prepareInsertDoc(doc2)
            )
        )

        mongoCollection1.updateOne(
            Filters.eq("_id", uuid1),
            Updates.push("ps_con_${connection.name}", insertDoc2)
        )
        mongoCollection2.updateOne(
            Filters.eq("_id", uuid2),
            Updates.push("ps_con_${connection.name}", insertDoc1)
        )
    }


    override fun take(
        path: QueryPath,
        terminal: PolyTerminal.Take
    ): PolyResult.Documents {
        if (path.segments.size == 1) {
            val segment = path.segments[0] as QuerySegment.Collection
            val mongoCollection = mongoDatabase.getCollection(segment.name)
            val result = if (segment.condition == null) mongoCollection.find() else
                mongoCollection.find(conditionToFilter(segment.condition))

            return PolyResult.Documents(result.map {
                parseResult(mapOf(segment.name to it), terminal.fields)
            }.toList())
        }
        if (path.segments.size == 2) {
            val parentSegment = path.segments[0] as QuerySegment.Collection

            when (val subSegment = path.segments[1]) {
                is QuerySegment.Collection -> {
                    if (parentSegment.condition == null && !terminal.fields.map { it.segment }
                            .contains(parentSegment.name)) {
                        val mongoSubCollection =
                            mongoDatabase.getCollection(
                                CollectionRef(
                                    parentSegment.name,
                                    subSegment.name
                                ).toPostgresPath()
                            )
                        val subDocs = if (subSegment.condition == null) mongoSubCollection.find().toList()
                        else mongoSubCollection.find(conditionToFilter(subSegment.condition)).toList()

                        return PolyResult.Documents(subDocs.map {
                            parseResult(mapOf(subSegment.name to it), terminal.fields)
                        }.toList())
                    } else {
                        val mongoParentCollection = mongoDatabase.getCollection(parentSegment.name)
                        if (subSegment.condition == null) {
                            val parentDocs = if (parentSegment.condition == null) mongoParentCollection.find()
                            else mongoParentCollection.find(conditionToFilter(parentSegment.condition))

                            return PolyResult.Documents(buildList {
                                parentDocs.forEach { parentDoc ->
                                    val subDocs =
                                        (parentDoc["ps_sub_${subSegment.name}"] as? List<*>)?.filterIsInstance<Document>() as List<Document>
                                    for (subDoc in subDocs) {
                                        add(
                                            parseResult(
                                                mapOf(parentSegment.name to parentDoc, subSegment.name to subDoc),
                                                terminal.fields
                                            )
                                        )
                                    }
                                }
                            })
                        } else {
                            val parentDocs = if (parentSegment.condition == null) mongoParentCollection.find(
                                Filters.elemMatch(
                                    "ps_sub_${subSegment.name}",
                                    conditionToFilter(subSegment.condition)
                                )
                            ) else mongoParentCollection.find(
                                Filters.and(
                                    conditionToFilter(parentSegment.condition),
                                    Filters.elemMatch(
                                        "ps_sub_${subSegment.name}",
                                        conditionToFilter(subSegment.condition)
                                    )
                                )
                            )

                            return PolyResult.Documents(buildList {
                                parentDocs.forEach { parentDoc ->
                                    val subDocs =
                                        ((parentDoc["ps_sub_${subSegment.name}"] as? List<*>)?.filterIsInstance<Document>() as List<Document>)
                                            .filter { checkCondition(it, subSegment.condition) }
                                    for (subDoc in subDocs) {
                                        add(
                                            parseResult(
                                                mapOf(parentSegment.name to parentDoc, subSegment.name to subDoc),
                                                terminal.fields
                                            )
                                        )
                                    }
                                }
                            })
                        }
                    }
                }

                is QuerySegment.Connection -> {
                    TODO()
                }
            }
        } else {
            TODO()
            /*val newTerminal = terminal.fields.filter { it.segment != path.segments[0]. }
            val docs = take(QueryPath(path.segments.subList(2, path.segments.size - 1)), )
            return take()*/
        }
    }

    private fun parseResult(documents: Map<String, Document>, fields: List<FieldRef>): PolyData {
        return buildMap {
            for (field in fields) {
                val segmentDoc = documents[field.segment]
                checkNotNull(segmentDoc) { "document for segment ${field.segment} missing" }
                when (field) {
                    is FieldRef.Named -> put(
                        "${field.segment}.${field.field}",
                        parsePolyValue(segmentDoc["ps_f_${field.field}"])
                    )

                    is FieldRef.Wildcard -> segmentDoc.entries.filter { it.key.startsWith("ps_f_") }
                        .forEach {
                            put(
                                "${field.segment}.${it.key.substring(5)}", parsePolyValue(it.value)
                            )
                        }

                }
            }
        }
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
            is Condition.Comparison.Equals -> Filters.eq("ps_f_${condition.field}", prepareValue(condition.value))
            is Condition.Comparison.LessThan -> Filters.lt("ps_f_${condition.field}", prepareValue(condition.value)!!)
            is Condition.Comparison.GreaterThan -> Filters.gt(
                "ps_f_${condition.field}",
                prepareValue(condition.value)!!
            )

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
        path: QueryPath,
        terminal: PolyTerminal.Count
    ): PolyResult.Count {
        TODO("Not yet implemented")
    }


    private fun prepareValue(value: PolyValue): Any? {
        return value.value
    }
}