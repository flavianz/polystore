package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
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

        val collectionName = collectionModel.name

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
            val parentCollectionName = parentCollection.toCollectionRef().leafName()
            val mongoParentCollection = mongoDatabase.getCollection(parentCollectionName)
            mongoParentCollection.updateOne(
                Filters.eq("_id", instruction.collectionPath.parentDoc().uuid),
                Updates.push("ps_sub_${collectionRef.leafName()}", document)
            )
        }

        mongoDatabase.getCollection(collectionRef.leafName()).insertOne(document)
    }

    override fun updateDocument(instruction: UpdateObjectInstruction) {
        if (instruction.documentPath.parentCollection().hasParentDoc()) {
            // update parent collection
            val parentCollection =
                instruction.documentPath.parentCollection().parentDoc().parentCollection().toCollectionRef()
            val collectionName = "ps_sub_${instruction.documentPath.parentCollection().toCollectionRef().leafName()}"
            val mongoCollection = mongoDatabase.getCollection(parentCollection.leafName())

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
            mongoDatabase.getCollection(instruction.documentPath.parentCollection().toCollectionRef().leafName())
        mongoCollection.updateOne(
            Filters.eq("_id", instruction.documentPath.uuid),
            Updates.combine(
                instruction.data.map { Updates.set("ps_f_${it.key}", prepareValue(it.value)) }
            )
        )

        val collectionName = instruction.documentPath.parentCollection().leafName()
        val connection = DatabaseManager.getConnectionOrNull(collectionName)

        if (connection != null) {
            // update connected documents
            val connectedCollection =
                if (connection.collection1Name == collectionName) connection.collection2Name else connection.collection1Name
            val connectionName = "ps_con_${connection.name}"
            val mongoDoc = mongoCollection.find(Filters.eq("_id", instruction.documentPath.uuid)).firstOrNull()
            checkNotNull(mongoDoc) { "updated mongo doc does not exist" }

            val connectedDocs = (mongoDoc[connectionName] as List<*>?)?.filterIsInstance<Document>() ?: emptyList()
            val ids = connectedDocs.map { it["_id"] as UUID }

            val mongoConnectedCollection = mongoDatabase.getCollection(connectedCollection)
            mongoConnectedCollection.updateMany(
                Filters.`in`("_id", ids),
                Updates.combine(
                    instruction.data.map {
                        Updates.set(
                            "ps_sub_${collectionName}.$.ps_doc.ps_f_${it.key}",
                            prepareValue(it.value)
                        )
                    })
            )
        }
    }

    override fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String,
        uuid1: UUID,
        collection2Name: String,
        uuid2: UUID,
        connectionData: PolyData
    ) {
        val mongoCollection1 = mongoDatabase.getCollection(collection1Name)
        val mongoCollection2 = mongoDatabase.getCollection(collection2Name)

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
        val requestedSegments = terminal.fields.map { it.segment }.toSet()

        var contexts: List<Map<String, Document>> = listOf(emptyMap())

        path.segments.forEachIndexed { index, rawSegment ->
            println("=== segment $index: $rawSegment ===")
            println("contexts before: ${contexts.size}")
            contexts = if (index == 0) {
                rawSegment as QuerySegment.Collection
                val col = mongoDatabase.getCollection(rawSegment.name)
                val docs = if (rawSegment.condition == null) col.find().toList()
                else col.find(conditionToFilter(rawSegment.condition)).toList()
                docs.map { mapOf(rawSegment.name to it) }

            } else {
                when (rawSegment) {
                    is QuerySegment.Collection -> {
                        val prevSegment = path.segments[index - 1] as QuerySegment.Collection
                        val isDirectSubcollection = prevSegment.condition == null
                                && prevSegment.name !in requestedSegments
                                && contexts.all { it.size == 1 }

                        if (isDirectSubcollection) {
                            val col = mongoDatabase.getCollection(rawSegment.name)

                            val docs = if (rawSegment.condition == null) col.find().toList()
                            else col.find(conditionToFilter(rawSegment.condition)).toList()
                            docs.map { mapOf(rawSegment.name to it) }
                        } else {
                            contexts.flatMap { ctx ->
                                expandCollection(ctx, ctx.keys.last(), rawSegment)
                            }
                        }
                    }

                    is QuerySegment.Connection -> {
                        contexts.flatMap { ctx ->
                            expandConnection(ctx, rawSegment)
                        }
                    }
                }
            }
            println("contexts after: ${contexts.size}")
            if (contexts.isNotEmpty()) println("first ctx keys: ${contexts.first().keys}")
        }

        println(contexts)

        return PolyResult.Documents(contexts.map { takeResultFields(it, terminal.fields) })
    }

    private fun expandCollection(
        ctx: Map<String, Document>,
        parentCollectionName: String,
        segment: QuerySegment.Collection
    ): List<Map<String, Document>> {
        val parentDoc = ctx.values.last()
        val embeddedKey = "ps_sub_${segment.name}"
        val parentId = parentDoc["_id"]

        val subDocs: List<Document> = if (segment.condition == null) {
            (parentDoc[embeddedKey] as? List<*>)
                ?.filterIsInstance<Document>()
                ?: emptyList()
        } else {
            val col = mongoDatabase.getCollection(parentCollectionName)
            val refetchedParent = col.find(
                Filters.and(
                    Filters.eq("_id", parentId),
                    Filters.elemMatch(embeddedKey, conditionToFilter(segment.condition))
                )
            ).firstOrNull() ?: return emptyList()

            ((refetchedParent[embeddedKey] as? List<*>)
                ?.filterIsInstance<Document>()
                ?: emptyList())
                .filter { checkCondition(it, segment.condition) }
        }

        return subDocs.map { ctx + (segment.name to it) }
    }

    private fun expandConnection(
        ctx: Map<String, Document>,
        segment: QuerySegment.Connection
    ): List<Map<String, Document>> {
        val parentDoc = ctx.values.last()
        val parentCollectionName = ctx.keys.last()
        val parentId = parentDoc["_id"]
        val embeddedKey = "ps_con_${segment.connectionName}"

        // The parent may be an embedded snapshot (e.g. from ps_sub_*) which won't have
        // ps_con_* fields — always re-fetch the live document by _id
        val col = mongoDatabase.getCollection(parentCollectionName)
        val liveParentDoc = col.find(Filters.eq("_id", parentId)).firstOrNull()
            ?: return emptyList()

        val entries: List<Document> = if (segment.connectionCondition == null && segment.collectionCondition == null) {
            (liveParentDoc[embeddedKey] as? List<*>)
                ?.filterIsInstance<Document>()
                ?: emptyList()
        } else {
            val elemMatchFilter = listOfNotNull(
                segment.connectionCondition?.let { cond ->
                    Filters.elemMatch(embeddedKey, conditionToFilter(cond, "ps_rel"))
                },
                segment.collectionCondition?.let { cond ->
                    Filters.elemMatch(embeddedKey, conditionToFilter(cond, "ps_doc"))
                }
            ).let { filters ->
                if (filters.size == 1) Filters.and(Filters.eq("_id", parentId), filters[0])
                else Filters.and(Filters.eq("_id", parentId), Filters.and(filters))
            }

            val refetchedParent = col.find(elemMatchFilter).firstOrNull()
                ?: return emptyList()

            ((refetchedParent[embeddedKey] as? List<*>)
                ?.filterIsInstance<Document>()
                ?: emptyList())
                .filter { entry ->
                    val rel = (entry["ps_rel"] as? Document) ?: Document()
                    val doc = (entry["ps_doc"] as? Document) ?: Document()
                    (segment.connectionCondition == null || checkCondition(rel, segment.connectionCondition))
                            && (segment.collectionCondition == null || checkCondition(doc, segment.collectionCondition))
                }
        }

        return entries.map { entry ->
            val relDoc = (entry["ps_rel"] as? Document) ?: Document()
            val connectedDoc = (entry["ps_doc"] as? Document) ?: Document()
            ctx + (segment.connectionName to relDoc) + (segment.collectionName to connectedDoc)
        }
    }

    private fun takeResultFields(documents: Map<String, Document>, fields: List<FieldRef>): PolyData {
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

    private fun conditionToFilter(condition: Condition, prefix: String = ""): Bson {
        fun fieldName(field: String) = if (prefix.isEmpty()) "ps_f_$field" else "$prefix.ps_f_$field"
        return when (condition) {
            is Condition.Comparison.Equals -> Filters.eq(fieldName(condition.field), prepareValue(condition.value))
            is Condition.Comparison.LessThan -> Filters.lt(fieldName(condition.field), prepareValue(condition.value)!!)
            is Condition.Comparison.GreaterThan -> Filters.gt(
                fieldName(condition.field),
                prepareValue(condition.value)!!
            )

            is Condition.Logic -> Filters.and(
                conditionToFilter(condition.left, prefix),
                conditionToFilter(condition.right, prefix)
            )

            is Condition.Not -> Filters.not(conditionToFilter(condition.condition, prefix))
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