package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import kotlin.collections.emptyList

class MongoDriver(val mongoDatabase: MongoDatabase) : DatabaseDriver {
    override fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        mongoDatabase.createCollection(collectionName)

        registerCollection(collectionName, schema, parentCollectionName)
    }

    override fun dropCollection(collection: CollectionModel) {
        dropCollectionRecursive(collection)
    }

    override fun dropConnection(connection: ConnectionModel) {
        mongoDatabase.getCollection("ps_config_connections").deleteOne(Filters.eq("name", connection.name))
    }

    private fun dropCollectionRecursive(collection: CollectionModel) {
        for(child in collection.childCollections) {
            dropCollectionRecursive(DatabaseManager.getCollectionModel(child))
        }
        mongoDatabase.getCollection(collection.name).drop()
        mongoDatabase.getCollection("ps_config_collections").deleteOne(Filters.eq("name", collection.name))
    }

    override fun createConnection(connection: ConnectionModel) {
        registerConnection(
            connection.name,
            connection.collection1Name,
            connection.collection2Name,
            connection.connectionDataSchema
        )
    }

    override fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID?) {
        val document = Document().append("_id", uuid)
        for (field in data) {
            document.append("ps_f_${field.key}", prepareValue(field.value))
        }

        if (collection.hasParentCollection()) {
            val mongoParentCollection = mongoDatabase.getCollection(collection.parentCollection!!)
            mongoParentCollection.updateOne(
                Filters.eq("_id", parentDocUuid),
                Updates.push("ps_sub_${collection.name}", document)
            )

            val parentCollection = DatabaseManager.getCollectionModel(collection.parentCollection)
            if(parentCollection.hasParentCollection()) {
                val mongoParentParentCollection = mongoDatabase.getCollection(parentCollection.parentCollection!!)
                mongoParentParentCollection.updateOne(
                    Filters.eq("ps_sub_${parentCollection.name}._id", parentDocUuid),
                    Updates.addToSet("ps_sub_${parentCollection.name}.$.ps_sub_${collection.name}", uuid)
                )
            }
        }

        mongoDatabase.getCollection(collection.name).insertOne(document)
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
            val ids = connectedDocs.map { (it["ps_doc"] as Document)["_id"] as UUID }

            val mongoConnectedCollection = mongoDatabase.getCollection(connectedCollection)
            mongoConnectedCollection.updateMany(
                Filters.`in`("_id", ids),
                Updates.combine(
                    instruction.data.map {
                        Updates.set(
                            "ps_con_${connection.name}.$[elem].ps_doc.ps_f_${it.key}",
                            prepareValue(it.value)
                        )
                    }),
                UpdateOptions().arrayFilters(listOf(Filters.eq("elem.ps_doc._id", instruction.documentPath.uuid)))
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
                "ps_rel" to connectionData.map { "ps_f_${it.key}" to prepareValue(it.value) }.toMap(),
                "ps_doc" to prepareInsertDoc(doc1)
            )
        )
        val insertDoc2 = Document(
            mapOf(
                "ps_rel" to connectionData.map { "ps_f_${it.key}" to prepareValue(it.value) }.toMap(),
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
    ): List<PolyData> {
        check(path.segments.isNotEmpty()) { "empty query" }
        val docsBySegment = mutableMapOf<String, List<MongoPolyObject>>()
        val segments = path.segments
        var i = 0
        when (val firstSegment = segments[0]) {
            is QuerySegment.Collection -> {
                if (segments.getOrNull(1) is QuerySegment.Collection) {
                    val parentDocs = fetchTwoCollectionSegments(
                        firstSegment,
                        segments[1] as QuerySegment.Collection
                    )
                    docsBySegment[firstSegment.name] = parentDocs.keys.toList()
                    docsBySegment[segments[1].collectionName()] =
                        parentDocs.values.flatten()

                    i += 2
                } else {
                    val docs = fetchCollectionSegment(firstSegment)
                    docsBySegment[firstSegment.name] = docs

                    i++
                }
            }

            is QuerySegment.Connection -> {
                val connectionDocs = fetchConnectionSegment(firstSegment, null)
                docsBySegment[firstSegment.collectionName] =
                    connectionDocs.values.distinctBy { it.id() }
                docsBySegment[firstSegment.connectionName] = connectionDocs.keys.toList()

                i += 1
            }
        }
        while (i < segments.size) {
            val previousSegment = segments[i - 1]
            val previousSegmentDocs = docsBySegment[previousSegment.collectionName()]
                ?: throw IllegalStateException("segment was not fetched")
            if (previousSegmentDocs.isEmpty()) {
                return emptyList()
            }
            when (val segment = segments[i]) {
                is QuerySegment.Collection -> {
                    when (previousSegment) {
                        is QuerySegment.Connection -> {
                            docsBySegment[segment.name] =
                                previousSegmentDocs.flatMap {
                                    check(it is MongoPolyCompleteDocument)
                                    it.getSubCollectionDocuments(segment.name) }
                            i++
                        }

                        is QuerySegment.Collection -> {
                            val segmentIds = previousSegmentDocs.flatMap {
                                check(it is MongoPolyDocument)
                                it.getSubCollectionIds(segment.name)
                            }

                            val combinedSegment = withIdCondition(segment, segmentIds)

                            if (segments.getOrNull(i + 1) is QuerySegment.Collection) {
                                val parentDocs = fetchTwoCollectionSegments(
                                    combinedSegment,
                                    segments[i + 1] as QuerySegment.Collection
                                )
                                docsBySegment[segment.name] = parentDocs.keys.toList()
                                docsBySegment[segments[i + 1].collectionName()] =
                                    parentDocs.values.flatten()
                                i += 2
                            } else {
                                val docs = fetchCollectionSegment(combinedSegment)
                                docsBySegment[combinedSegment.name] = docs

                                i++
                            }
                        }
                    }
                }

                is QuerySegment.Connection -> {
                    val segmentIds = previousSegmentDocs.map {
                        check(it is MongoPolyDocument)
                        it.id()
                    }

                    val connectionDocs = fetchConnectionSegment(
                        segment, segmentIds
                    )
                    docsBySegment[segment.collectionName] =
                        connectionDocs.values.distinctBy { it.id() }
                    docsBySegment[segment.connectionName] = connectionDocs.keys.toList()

                    i++
                }
            }
        }

        var completeDocPaths: List<Map<String, MongoPolyObject>>? = null

        segments.forEachIndexed { index, segment ->
            if (completeDocPaths == null) {
                completeDocPaths = when (segment) {
                    is QuerySegment.Collection -> docsBySegment[segment.name]!!.map { mapOf(segment.name to it) }
                    is QuerySegment.Connection -> docsBySegment[segment.collectionName]!!.map { mapOf(segment.collectionName to it) } +
                            docsBySegment[segment.connectionName]!!.map { mapOf(segment.connectionName to it) }
                }
            } else {
                completeDocPaths = buildList {
                    for (docPath in completeDocPaths) {
                        assert(index >= 1)
                        val previousDoc = docPath[segments[index - 1].collectionName()]!!
                        when (segment) {
                            is QuerySegment.Collection -> {
                                check(previousDoc is MongoPolyDocument)
                                val ids = previousDoc.getSubCollectionIds(segment.name)
                                val allDocs = docsBySegment[segment.name]!!
                                for (doc in allDocs.filter { check(it is MongoPolyDocument); it.id() in ids }) {
                                    add(docPath + (segment.name to doc))
                                }
                            }

                            is QuerySegment.Connection -> {
                                check(previousDoc is MongoPolyDocument)
                                val ids = previousDoc.getConnectedIds(segment.connectionName)
                                val collectionDocs = docsBySegment[segment.collectionName]!!
                                val availableRelations = buildMap {
                                    for (doc in collectionDocs.filter { check(it is MongoPolyDocument); it.id() in ids }) {
                                        check(doc is MongoPolyCompleteDocument)
                                        for (con in doc.getConnectionDocuments(segment.connectionName)
                                            .filter { it.getSubDoc().id() == previousDoc.id() }.filter {
                                                checkCondition(
                                                    it.getConnectionData().doc,
                                                    segment.connectionCondition
                                                )
                                            }) {
                                            put(con, doc)
                                        }
                                    }
                                }
                                for (relationship in availableRelations) {
                                    add(
                                        docPath + (segment.collectionName to relationship.value)
                                                + (segment.connectionName to relationship.key.getConnectionData())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        checkNotNull(completeDocPaths)

        return completeDocPaths.map { doc ->
            takeResultFields(
                doc.filterValues { it is MongoPolyData }.toMap() as Map<String, MongoPolyData>,
                terminal.fields
            )
        }
    }

    private fun withIdCondition(segment: QuerySegment.Collection, ids: List<UUID>): QuerySegment.Collection {
        val idCondition = Condition.In("_id", ids.map { PolyValue.of(it) })
        return QuerySegment.Collection(
            segment.name,
            if (segment.condition == null) idCondition else Condition.Logic.And(segment.condition, idCondition)
        )
    }

    private fun fetchCollectionSegment(segment: QuerySegment.Collection): List<MongoPolyDocument> {
        val mongoCollection = mongoDatabase.getCollection(segment.name)
        val result = if (segment.condition == null) mongoCollection.find() else
            mongoCollection.find(conditionToFilter(segment.condition))

        return result.map { MongoPolyCompleteDocument(it) }.toList()
    }

    private fun fetchTwoCollectionSegments(
        parentSegment: QuerySegment.Collection,
        subSegment: QuerySegment.Collection
    ): Map<MongoPolyDocument, List<MongoPolyDocument>> {
        val mongoParentCollection = mongoDatabase.getCollection(parentSegment.name)
        if (subSegment.condition == null) {
            val parentDocs = (if (parentSegment.condition == null) mongoParentCollection.find()
            else mongoParentCollection.find(conditionToFilter(parentSegment.condition))).map { MongoPolyCompleteDocument(it) }

            return parseSubDocs(parentDocs.toList(), subSegment.name)
        } else {
            val parentDocs = (if (parentSegment.condition == null) mongoParentCollection.find(
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
            )).map { MongoPolyCompleteDocument(it) }

            // manually filter sub docs to avoid false positives (required)
            val allSubDocs = parseSubDocs(parentDocs.toList(), subSegment.name)
            return allSubDocs.map { subDoc ->
                subDoc.key to subDoc.value.filter { doc ->
                    checkCondition(
                        doc.doc,
                        subSegment.condition
                    )
                }
            }.toMap()
        }
    }

    private fun fetchConnectionSegment(
        segment: QuerySegment.Connection,
        startCollectionIds: List<UUID>?
    ): Map<MongoPolyConnection, MongoPolyCompleteDocument> {
        val filters = mutableListOf<Bson>()
        if (segment.collectionCondition != null) {
            filters.add(conditionToFilter(segment.collectionCondition))
        }
        if (segment.connectionCondition != null) {
            filters.add(
                Filters.elemMatch(
                    "ps_con_${segment.connectionName}",
                    conditionToFilter(segment.connectionCondition, "ps_rel.")
                )
            )
        }
        if (startCollectionIds != null) {
            filters.add(
                Filters.elemMatch(
                    "ps_con_${segment.connectionName}",
                    Filters.`in`("ps_doc._id", startCollectionIds)
                )
            )
        }
        val collectionDocs = mongoDatabase.getCollection(segment.collectionName)
            .find(if (filters.isNotEmpty()) Filters.and(filters) else Filters.empty())
        return buildMap {
            collectionDocs.forEach { parentDoc ->
                val relations = (parentDoc["ps_con_${segment.connectionName}"] as List<*>).filterIsInstance<Document>()
                relations.filter {
                    startCollectionIds == null || ((it["ps_doc"] as Document)["_id"] as UUID) in startCollectionIds
                }.filter {
                    checkCondition(it["ps_rel"] as Document, segment.connectionCondition)
                }
                    .forEach {
                        put(MongoPolyConnection(it), MongoPolyCompleteDocument(parentDoc))
                    }
            }
        }
    }

    private fun parseSubDocs(
        parentDocs: List<MongoPolyCompleteDocument>,
        subCollectionName: String,
    ): Map<MongoPolyDocument, List<MongoPolyDocument>> {
        return parentDocs.associate { it to it.getSubCollectionDocuments(subCollectionName) }
    }

    private fun takeResultFields(documents: Map<String, MongoPolyData>, fields: List<FieldRef>): PolyData {
        return buildMap {
            for (field in fields) {
                val segmentDoc = documents[field.segment]
                when (field) {
                    is FieldRef.Named -> put(
                        "${field.segment}.${field.field}",
                        PolyValue.of(segmentDoc?.getField(field.field))
                    )

                    is FieldRef.Wildcard -> (segmentDoc?.entries()?.entries ?: emptyList()).filter { it.key.startsWith("ps_f_") || it.key == "_id" }
                        .forEach {
                            put(
                                "${field.segment}.${if (it.key == "_id") "_id" else it.key.substring(5)}",
                                PolyValue.of(it.value)
                            )
                        }

                }
            }
        }
    }

    private fun conditionToFilter(condition: Condition, prefix: String = ""): Bson {
        return when (condition) {
            is Condition.Comparison.Equals -> Filters.eq(
                "${prefix}ps_f_${condition.field}",
                prepareValue(condition.value)
            )

            is Condition.Comparison.LessThan -> Filters.lt(
                "${prefix}ps_f_${condition.field}",
                prepareValue(condition.value)!!
            )

            is Condition.Comparison.GreaterThan -> Filters.gt(
                "${prefix}ps_f_${condition.field}",
                prepareValue(condition.value)!!
            )

            is Condition.Logic.And -> Filters.and(conditionToFilter(condition.left), conditionToFilter(condition.right))
            is Condition.Logic.Or -> Filters.or(conditionToFilter(condition.left), conditionToFilter(condition.right))
            is Condition.Not -> Filters.not(conditionToFilter(condition.condition))
            is Condition.In -> Filters.`in`(condition.field, condition.list.map { prepareValue(it) })
        }
    }

    private fun checkCondition(document: Map<String, Any?>, condition: Condition?): Boolean {
        return when (condition) {
            is Condition.Comparison.Equals -> PolyValue.of(document["ps_f_${condition.field}"]) == condition.value
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
            is Condition.In -> document["ps_f_${condition.field}"] in condition.list
            null -> true
        }
    }

    override fun count(
        path: QueryPath,
        terminal: PolyTerminal.Count
    ): PolyResult.Count {
        TODO("Not yet implemented")
    }

    override fun init() {
        val existsCollections = mongoDatabase.listCollections()
            .filter(Document("name", "ps_config_collections"))
            .first() != null
        if (!existsCollections) {
            mongoDatabase.createCollection("ps_config_collections")
        }
        val existsConnections = mongoDatabase.listCollections()
            .filter(Document("name", "ps_config_connections"))
            .first() != null
        if (!existsConnections) {
            mongoDatabase.createCollection("ps_config_connections")
        }
    }

    override fun getDatabaseSchema(): DatabaseSchema {
        val collectionDocs = mongoDatabase.getCollection("ps_config_collections").find().toList()
        val connectionDocs = mongoDatabase.getCollection("ps_config_connections").find().toList()

        fun parseFields(fields: List<*>): PolySchema {
            return fields.filterIsInstance<Document>()
                .associate { field -> field["name"] as String to DataType.valueOf((field["type"] as String).uppercase()) }
        }

        val collections = collectionDocs.map {
            CollectionModel(
                it["name"] as String,
                parseFields(it["fields"] as List<*>),
                mutableListOf(),
                it["parent_collection"] as String?
            )
        }
        val connections = connectionDocs.map {
            ConnectionModel(
                it["name"] as String,
                it["collection1"] as String,
                it["collection2"] as String,
                parseFields(it["fields"] as List<*>),
            )
        }

        // add child collections to schema
        addChildCollections(collections)

        return DatabaseSchema(collections.toSet(), connections.toSet())
    }


    private fun prepareValue(value: PolyValue): Any? {
        return value.value
    }


    private fun registerCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        val mongoCollection = mongoDatabase.getCollection("ps_config_collections")
        mongoCollection.insertOne(
            Document(
                mapOf(
                    "name" to collectionName,
                    "fields" to schema.entries.map {
                        Document(
                            mapOf(
                                "name" to it.key,
                                "type" to it.value
                            )
                        )
                    },
                    "parent_collection" to parentCollectionName
                )
            )
        )
    }

    private fun registerConnection(
        connectionName: String,
        collection1Name: String,
        collection2Name: String,
        schema: PolySchema
    ) {
        val mongoCollection = mongoDatabase.getCollection("ps_config_connections")
        mongoCollection.insertOne(
            Document(
                mapOf(
                    "name" to connectionName,
                    "collection1" to collection1Name,
                    "collection2" to collection2Name,
                    "fields" to schema.entries.map {
                        Document(
                            mapOf(
                                "name" to it.key,
                                "type" to it.value
                            )
                        )
                    }
                )))
    }
}

private abstract class MongoPolyObject(val doc: Document)

private open class MongoPolyData(doc: Document) : MongoPolyObject(doc) {
    fun getField(name: String): Any? {
        return doc["ps_f_$name"]
    }

    fun entries(): Map<String, Any?> {
        return doc.filter { it.key == "_id" || it.key.startsWith("ps_f_") }
    }
}

private abstract class MongoPolyDocument(doc: Document) : MongoPolyData(doc) {
    fun id(): UUID {
        return doc["_id"] as UUID
    }

    abstract fun getSubCollectionIds(name: String): List<UUID>
    abstract fun getConnectedIds(name: String): List<UUID>
}


private class MongoPolyCompleteDocument(doc: Document) : MongoPolyDocument(doc) {
    fun getSubCollectionDocuments(name: String): List<MongoPolySubDocument> {
        val subCollection = doc["ps_sub_${name}"] ?: return emptyList()
        check(subCollection is List<*>) { "sub collection $name does not exist on ${id()}" }
        return subCollection.filterIsInstance<Document>().map { MongoPolySubDocument(it) }
    }

    override fun getSubCollectionIds(name: String): List<UUID> {
        return getSubCollectionDocuments(name).map { it.id() }
    }

    fun getConnectionDocuments(name: String): List<MongoPolyConnection> {
        val subCollection = doc["ps_con_${name}"]
        check(subCollection is List<*>) { "connection $name does not exist on ${id()}" }
        return subCollection.filterIsInstance<Document>().map { MongoPolyConnection(it) }
    }

    override fun getConnectedIds(name: String): List<UUID> {
        return getConnectionDocuments(name).map { it.getSubDoc().id() }
    }
}

private class MongoPolySubDocument(doc: Document) : MongoPolyDocument(doc) {
    override fun getSubCollectionIds(name: String): List<UUID> {
        val subCollection = doc["ps_sub_${name}"] ?: return emptyList()
        check(subCollection is List<*>) { "sub collection $name does not exist on ${id()}" }
        if (subCollection.isEmpty()) {
            return emptyList()
        }
        return if (subCollection.first()!! is UUID) {
            subCollection.filterIsInstance<UUID>()
        } else {
            subCollection.filterIsInstance<Document>().map { it["_id"] as UUID }
        }
    }

    override fun getConnectedIds(name: String): List<UUID> {
        val connection = doc["ps_con_${name}"] ?: return emptyList()
        check(connection is List<*>) { "connection $name does not exist on ${id()}" }
        if (connection.isEmpty()) {
            return emptyList()
        }
        return if (connection.first()!! is UUID) {
            connection.filterIsInstance<UUID>()
        } else {
            connection.filterIsInstance<Document>().map { (it["ps_doc"] as Document)["_id"] as UUID }
        }
    }
}

private class MongoPolyConnection(doc: Document) : MongoPolyObject(doc) {
    fun getSubDoc(): MongoPolySubDocument {
        return MongoPolySubDocument(doc["ps_doc"] as Document)
    }
    fun getConnectionData(): MongoPolyData {
        return MongoPolyData(doc["ps_doc"] as Document)
    }
}