package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.DocumentPath
import ch.flavianz.model.PolySchema
import ch.flavianz.model.GetQuery
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.PolyDriverQueryDuration
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import kotlin.collections.emptyList
import kotlin.sequences.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

class MongoDriver(val mongoDatabase: MongoDatabase) : DatabaseDriver {
    override fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        mongoDatabase.createCollection(collectionName)

        registerCollection(collectionName, schema, parentCollectionName)
    }

    override fun dropCollection(collection: CollectionModel) {
        dropCollectionRecursive(collection)
    }

    override fun dropConnection(connectionModel: ConnectionModel) {
        mongoDatabase.getCollection("ps_config_connections").deleteOne(Filters.eq("name", connectionModel.name))
    }

    private fun dropCollectionRecursive(collection: CollectionModel) {
        for (child in collection.childCollections) {
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
            document.append(field.key, field.value)
        }

        if (collection.hasParentCollection()) {
            val mongoParentCollection = mongoDatabase.getCollection(collection.parentCollection!!)
            mongoParentCollection.updateOne(
                Filters.eq("_id", parentDocUuid),
                Updates.push("_sub_${collection.name}", document)
            )

            val parentCollection = DatabaseManager.getCollectionModel(collection.parentCollection)
            if (parentCollection.hasParentCollection()) {
                val mongoParentParentCollection = mongoDatabase.getCollection(parentCollection.parentCollection!!)
                mongoParentParentCollection.updateOne(
                    Filters.eq("_sub_${parentCollection.name}._id", parentDocUuid),
                    Updates.addToSet("_sub_${parentCollection.name}.$._sub_${collection.name}", uuid)
                )
            }
        }

        mongoDatabase.getCollection(collection.name).insertOne(document)
    }

    override fun updateDocument(documentPath: DocumentPath, data: PolyData) {
        if (documentPath.parentCollection().hasParentDoc()) {
            // update parent collection
            val parentCollection =
                documentPath.parentCollection().parentDoc().parentCollection().toCollectionRef()
            val collectionName = "_sub_${documentPath.parentCollection().toCollectionRef().leafName()}"
            val mongoCollection = mongoDatabase.getCollection(parentCollection.leafName())

            mongoCollection.updateOne(
                Filters.eq("${collectionName}._id", documentPath.uuid),
                Updates.combine(
                    data.map {
                        Updates.set(
                            "$collectionName.$.${it.key}",
                            it.value
                        )
                    }
                )
            )
        }
        val mongoCollection =
            mongoDatabase.getCollection(documentPath.parentCollection().toCollectionRef().leafName())
        mongoCollection.updateOne(
            Filters.eq("_id", documentPath.uuid),
            Updates.combine(
                data.map { Updates.set(it.key, it.value) }
            )
        )

        val collectionName = documentPath.parentCollection().leafName()
        val connection = DatabaseManager.getConnectionOrNull(collectionName)

        if (connection != null) {
            // update connected documents
            val connectedCollection =
                if (connection.collection1Name == collectionName) connection.collection2Name else connection.collection1Name
            val connectionName = "_con_${connection.name}"
            val mongoDoc = mongoCollection.find(Filters.eq("_id", documentPath.uuid)).firstOrNull()
            checkNotNull(mongoDoc) { "updated mongo doc does not exist" }

            val connectedDocs = (mongoDoc[connectionName] as List<*>?)?.filterIsInstance<Document>() ?: emptyList()
            val ids = connectedDocs.map { (it["_doc"] as Document)["_id"] as UUID }

            val mongoConnectedCollection = mongoDatabase.getCollection(connectedCollection)
            mongoConnectedCollection.updateMany(
                Filters.`in`("_id", ids),
                Updates.combine(
                    data.map {
                        Updates.set(
                            "_con_${connection.name}.$[elem]._doc.${it.key}",
                            it.value
                        )
                    }),
                UpdateOptions().arrayFilters(listOf(Filters.eq("elem._doc._id", documentPath.uuid)))
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
            if (it.key.startsWith("_con_") || it.key.startsWith("_col_")) {
                val docs = (it.value as List<*>).filterIsInstance<Document>()
                return@map it.key to docs.map { doc -> doc["_id"] }
            }
            return@map it.key to it.value
        }.toMap()

        val insertDoc1 = Document(
            mapOf(
                "_rel" to connectionData.map { it.key to it.value }.toMap(),
                "_doc" to prepareInsertDoc(doc1)
            )
        )
        val insertDoc2 = Document(
            mapOf(
                "_rel" to connectionData.map { it.key to it.value }.toMap(),
                "_doc" to prepareInsertDoc(doc2)
            )
        )

        mongoCollection1.updateOne(
            Filters.eq("_id", uuid1),
            Updates.push("_con_${connection.name}", insertDoc2)
        )
        mongoCollection2.updateOne(
            Filters.eq("_id", uuid2),
            Updates.push("_con_${connection.name}", insertDoc1)
        )
    }


    override fun get(
        query: GetQuery,
    ): TimedDriverResult<List<PolyData>> {
        val startTime = System.nanoTime()
        check(query.isNotEmpty()) { "empty query" }


        if (query.size == 1) {
            val segment = query[0]
            require(segment is QuerySegment.Collection) { "connection segment must be placed before a collection segment" }

            val mongoCollection = mongoDatabase.getCollection(segment.name)
            val condition = conditionToFilter(segment.condition)
            val collectionModel = DatabaseManager.getCollectionModel(segment.name)

            var compTime = Duration.ZERO
            print(
                mongoCollection.find(condition).projection(
                    Projections.exclude(
                        collectionModel.childCollections.map { "_sub_${it}" } +
                                collectionModel.getConnectedCollections().map { "_con_${it}" }
                    )
                ).explain().getEmbedded(listOf("executionStats", "executionTimeMillis"), Integer::class))
            println(
                " ${
                    measureTime {
                        mongoCollection.find(condition).projection(
                            Projections.exclude(
                                collectionModel.childCollections.map { "_sub_${it}" } +
                                        collectionModel.getConnectedCollections().map { "_con_${it}" }
                            )
                        ).toList()
                    }
                }")

            val result = measureTimedValue {
                mongoCollection.find(condition).projection(
                    Projections.exclude(
                        collectionModel.childCollections.map { "_sub_${it}" } +
                                collectionModel.getConnectedCollections().map { "_con_${it}" }
                    )
                ).asSequence().map { doc ->
                    measureTimedValue {
                        if (segment.only == null) doc.filter { !it.key.startsWith("_") || it.key == "_id" }
                            .mapKeys { "${segment.name}.${it.key}" }
                        else segment.only.associate {
                            "${segment.name}.$it" to doc[it]
                        }
                    }.let { compTime += it.duration; it.value }
                }.toList()
            }

            val data =
                result.value

            val execDuration = result.duration - compTime
            val buildDuration = (System.nanoTime() - startTime).nanoseconds - execDuration

            return TimedDriverResult(
                data,
                PolyDriverQueryDuration(
                    buildDuration,
                    execDuration
                ), listOf("list collection ${segment.name} with condition ${segment.condition}")
            )
        } else if (query.size == 2 && query[0] is QuerySegment.Collection && query[1] is QuerySegment.Collection) {
            val parentCol = query[0] as QuerySegment.Collection
            val childCol = query[1] as QuerySegment.Collection

            val mongoParentCollection = mongoDatabase.getCollection(parentCol.name)
            val parentCondition = conditionToFilter(parentCol.condition)
            val parentCollectionModel = DatabaseManager.getCollectionModel(parentCol.name)

            var compTime = Duration.ZERO

            val result = measureTimedValue {
                val query = mongoParentCollection.find(
                    Filters.and(
                        parentCondition,
                        Filters.elemMatch(
                            "_sub_${childCol.name}",
                            conditionToFilter(childCol.condition)
                        )
                    )
                ).projection(
                    Projections.exclude(
                        (parentCollectionModel.childCollections.map { "_sub_${it}" } - "_sub_${childCol.name}") +
                                parentCollectionModel.getConnectedCollections().map { "_con_${it}" }
                    )
                )
                buildList {
                    for (doc in query.asSequence()) {
                        measureTimedValue {
                            val parentData = (if (parentCol.only == null) {
                                doc.filter { !it.key.startsWith("_") || it.key == "_id" }
                                    .mapKeys { "${parentCol.name}.${it.key}" }
                            } else {
                                parentCol.only.associate {
                                    "${parentCol.name}.$it" to doc[it]
                                }
                            })
                            for (subDoc in (doc["_sub_${childCol.name}"] as? List<*>
                                ?: emptyList<Document>()).filterIsInstance<Document>()
                                .filter { checkCondition(it, childCol.condition) }) {
                                if (childCol.only == null) {
                                    add(parentData + subDoc.filter { !it.key.startsWith("_") || it.key == "_id" }
                                        .mapKeys { "${childCol.name}.${it.key}" })
                                } else {
                                    add(parentData + childCol.only.associate {
                                        "${childCol.name}.$it" to subDoc[it]
                                    })
                                }
                            }
                        }.let { compTime += it.duration; it.value }
                    }
                }
            }

            val data =
                result.value

            val execDuration = result.duration - compTime
            val buildDuration = (System.nanoTime() - startTime).nanoseconds - execDuration

            return TimedDriverResult(
                data,
                PolyDriverQueryDuration(
                    buildDuration,
                    execDuration
                ),
                listOf("list collection ${parentCol.name} with parent condition ${parentCol.condition} and child condition ${childCol.condition}")
            )
        } else if (query.size == 2 && query[0] is QuerySegment.Collection && query[1] is QuerySegment.Connection) {
            val collectionSegment = query[0] as QuerySegment.Collection
            val connectionSegment = query[1] as QuerySegment.Connection

            val mongoCollection = mongoDatabase.getCollection(collectionSegment.name)
            val collectionModel = DatabaseManager.getCollectionModel(collectionSegment.name)

            var compTime = Duration.ZERO

            val filters = buildList {
                collectionSegment.condition?.let {
                    add(conditionToFilter(it))
                }
                connectionSegment.connectionCondition?.let {
                    add(
                        Filters.elemMatch(
                            "_con_${connectionSegment.connectionName}",
                            conditionToFilter(it, "_rel.")
                        )
                    )
                }
                connectionSegment.collectionCondition?.let {
                    add(
                        Filters.elemMatch(
                            "_con_${connectionSegment.connectionName}",
                            conditionToFilter(it, "_doc.")
                        )
                    )
                }
            }

            val result = measureTimedValue {
                val query = mongoCollection.find(
                    if (filters.isEmpty()) Filters.empty() else Filters.and(filters)
                ).projection(
                    Projections.exclude(
                        collectionModel.childCollections.map { "_sub_${it}" } +
                                (collectionModel.getConnectedCollections()
                                    .map { "_con_${it}" } - "_con_${connectionSegment.connectionName}")
                    )
                )
                buildList {
                    for (doc in query.asSequence()) {
                        measureTimedValue {
                            val collectionData = (if (collectionSegment.only == null) {
                                doc.filter { !it.key.startsWith("_") || it.key == "_id" }
                                    .mapKeys { "${collectionSegment.name}.${it.key}" }
                            } else {
                                collectionSegment.only.associate {
                                    "${collectionSegment.name}.$it" to doc[it]
                                }
                            })
                            for (connection in (doc["_con_${connectionSegment.connectionName}"] as? List<*>
                                ?: emptyList<Document>()).filterIsInstance<Document>()
                                .filter {
                                    checkCondition(it["_rel"] as Document, connectionSegment.connectionCondition)
                                            && checkCondition(
                                        it["_doc"] as Document,
                                        connectionSegment.collectionCondition
                                    )
                                }) {
                                add(
                                    collectionData + (if (connectionSegment.connectionOnly == null) {
                                        (connection["_rel"] as Document)
                                            .mapKeys { "${connectionSegment.connectionName}.${it.key}" }
                                    } else {
                                        collectionData + connectionSegment.connectionOnly.associate {
                                            "${connectionSegment.connectionName}.$it" to connection[it]
                                        }
                                    }) + (if (connectionSegment.collectionOnly == null) {
                                        (connection["_doc"] as Document).filter { !it.key.startsWith("_") || it.key == "_id" }
                                            .mapKeys { "${connectionSegment.collectionName}.${it.key}" }
                                    } else {
                                        collectionData + connectionSegment.collectionOnly.associate {
                                            "${connectionSegment.collectionName}.$it" to connection[it]
                                        }
                                    }))

                            }
                        }.let { compTime += it.duration; it.value }
                    }
                }
            }

            val data =
                result.value

            val execDuration = result.duration - compTime
            val buildDuration = (System.nanoTime() - startTime).nanoseconds - execDuration

            return TimedDriverResult(
                data,
                PolyDriverQueryDuration(
                    buildDuration,
                    execDuration
                ),
                listOf(
                    buildString {
                        append("list collection ${collectionSegment.name}")
                        collectionSegment.condition?.let {
                            append("with condition ${collectionSegment.condition}")
                        }
                        connectionSegment.connectionCondition?.let {
                            append("with connection condition ${connectionSegment.connectionCondition}")
                        }
                        connectionSegment.collectionCondition?.let {
                            append("with collection condition ${connectionSegment.collectionCondition}")
                        }
                    }
                )
            )
        }

        val docsBySegment = mutableMapOf<String, List<MongoPolyObject>>()
        val segments = query
        var i = 0
        var totalQueryExecutionDuration = Duration.ZERO
        val executedQueries = mutableListOf<String>()

        fun <T> logMetrics(timedQueryValue: TimedQueryValue<T>): T {
            executedQueries.add(timedQueryValue.executedQuery)
            totalQueryExecutionDuration = totalQueryExecutionDuration.plus(timedQueryValue.duration)
            return timedQueryValue.value
        }

        when (val firstSegment = segments[0]) {
            is QuerySegment.Collection -> {
                if (segments.getOrNull(1) is QuerySegment.Collection) {
                    val parentDocs = logMetrics(
                        fetchTwoCollectionSegments(
                            firstSegment,
                            segments[1] as QuerySegment.Collection
                        )
                    )

                    docsBySegment[firstSegment.name] = parentDocs.keys.toList()
                    docsBySegment[segments[1].collectionName()] =
                        parentDocs.values.flatten()

                    i += 2
                } else {
                    val docs = logMetrics(fetchCollectionSegment(firstSegment))

                    docsBySegment[firstSegment.name] = docs

                    i++
                }
            }

            is QuerySegment.Connection -> {
                val connectionDocs = logMetrics(fetchConnectionSegment(firstSegment, null))

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
                val elapsedDuration = (System.nanoTime() - startTime).nanoseconds
                return TimedDriverResult(
                    emptyList(),
                    PolyDriverQueryDuration(
                        elapsedDuration.minus(totalQueryExecutionDuration),
                        totalQueryExecutionDuration
                    ),
                    executedQueries
                )
            }
            when (val segment = segments[i]) {
                is QuerySegment.Collection -> {
                    when (previousSegment) {
                        is QuerySegment.Connection -> {
                            docsBySegment[segment.name] =
                                previousSegmentDocs.flatMap {
                                    check(it is MongoPolyCompleteDocument)
                                    it.getSubCollectionDocuments(segment.name)
                                }
                            i++
                        }

                        is QuerySegment.Collection -> {
                            val segmentIds = previousSegmentDocs.flatMap {
                                check(it is MongoPolyDocument)
                                it.getSubCollectionIds(segment.name)
                            }

                            val combinedSegment = withIdCondition(segment, segmentIds)

                            if (segments.getOrNull(i + 1) is QuerySegment.Collection) {
                                val parentDocs = logMetrics(
                                    fetchTwoCollectionSegments(
                                        combinedSegment,
                                        segments[i + 1] as QuerySegment.Collection
                                    )
                                )

                                docsBySegment[segment.name] = parentDocs.keys.toList()
                                docsBySegment[segments[i + 1].collectionName()] =
                                    parentDocs.values.flatten()
                                i += 2
                            } else {
                                val docs = logMetrics(fetchCollectionSegment(combinedSegment))

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
                    }.toSet()

                    val connectionDocs = logMetrics(
                        fetchConnectionSegment(
                            segment, segmentIds
                        )
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
                completeDocPaths = when (segment) {
                    is QuerySegment.Collection -> {
                        val docsById = docsBySegment[segment.name]!!.associateBy {
                            check(it is MongoPolyDocument); it.id()
                        }
                        buildList {
                            for (docPath in completeDocPaths!!) {
                                val previousDoc = docPath[segments[index - 1].collectionName()]!!
                                check(previousDoc is MongoPolyDocument)
                                for (id in previousDoc.getSubCollectionIds(segment.name)) {
                                    docsById[id]?.let { add(docPath + (segment.name to it)) }
                                }
                            }
                        }
                    }

                    is QuerySegment.Connection -> {
                        // Build previousDocId -> [(connectionDoc, ownerDoc)] once
                        val byPreviousId =
                            mutableMapOf<Any, MutableList<Pair<MongoPolyConnection, MongoPolyDocument>>>()
                        for (doc in docsBySegment[segment.collectionName]!!) {
                            check(doc is MongoPolyCompleteDocument)
                            for (con in doc.getConnectionDocuments(segment.connectionName)) {
                                if (checkCondition(con.getConnectionData().doc, segment.connectionCondition)) {
                                    byPreviousId.getOrPut(con.getSubDoc().id()) { mutableListOf() }
                                        .add(con to doc)
                                }
                            }
                        }
                        buildList {
                            for (docPath in completeDocPaths!!) {
                                val previousDoc = docPath[segments[index - 1].collectionName()]!!
                                check(previousDoc is MongoPolyDocument)
                                for ((con, doc) in byPreviousId[previousDoc.id()].orEmpty()) {
                                    add(
                                        docPath + (segment.collectionName to doc)
                                                + (segment.connectionName to con.getConnectionData())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        checkNotNull(completeDocPaths)

        val data = completeDocPaths.map { doc ->
            takeResultFields(
                doc.filterValues { it is MongoPolyData }.toMap() as Map<String, MongoPolyData>,
                segments.flatMap {
                    when (it) {
                        is QuerySegment.Collection -> listOf(it.name to it.only)
                        is QuerySegment.Connection -> listOf(
                            it.connectionName to it.connectionOnly,
                            it.collectionName to it.collectionOnly
                        )
                    }
                }
            )
        }
        val elapsedTime = (System.nanoTime() - startTime).nanoseconds
        return TimedDriverResult(
            data,
            PolyDriverQueryDuration(elapsedTime.minus(totalQueryExecutionDuration), totalQueryExecutionDuration),
            executedQueries
        )
    }

    private fun withIdCondition(segment: QuerySegment.Collection, ids: List<UUID>): QuerySegment.Collection {
        val idCondition = Condition.In("_id", ids.toSet())
        return QuerySegment.Collection(
            segment.name,
            if (segment.condition == null) idCondition else Condition.Logic.And(segment.condition, idCondition)
        )
    }

    private fun fetchCollectionSegment(segment: QuerySegment.Collection): TimedQueryValue<List<MongoPolyDocument>> {
        val mongoCollection = mongoDatabase.getCollection(segment.name)

        val condition = if (segment.condition == null) Filters.empty() else conditionToFilter(segment.condition)
        val result = measureTimedValue {
            mongoCollection.find(condition).toList()
        }

        return TimedQueryValue(
            result.value.map { MongoPolyCompleteDocument(it) },
            result.duration,
            "list $mongoCollection with ${condition?.toString() ?: "no condition"}"
        )
    }

    private fun fetchTwoCollectionSegments(
        parentSegment: QuerySegment.Collection,
        subSegment: QuerySegment.Collection
    ): TimedQueryValue<Map<MongoPolyDocument, List<MongoPolyDocument>>> {
        val mongoParentCollection = mongoDatabase.getCollection(parentSegment.name)
        if (subSegment.condition == null) {
            val condition =
                if (parentSegment.condition == null) Filters.empty() else conditionToFilter(parentSegment.condition)
            val parentDocs = measureTimedValue {
                mongoParentCollection.find(condition).toList()
            }

            return TimedQueryValue(
                parseSubDocs(parentDocs.value.map {
                    MongoPolyCompleteDocument(
                        it
                    )
                }, subSegment.name),
                parentDocs.duration,
                "list $mongoParentCollection with ${condition?.toString() ?: "no condition"}"
            )
        } else {
            val condition = if (parentSegment.condition == null) Filters.elemMatch(
                "_sub_${subSegment.name}",
                conditionToFilter(subSegment.condition)
            ) else Filters.and(
                conditionToFilter(parentSegment.condition),
                Filters.elemMatch(
                    "_sub_${subSegment.name}",
                    conditionToFilter(subSegment.condition)
                )
            )
            val parentDocs = measureTimedValue {
                mongoParentCollection.find(condition).toList()
            }

            // manually filter sub docs to avoid false positives (required)
            val allSubDocs =
                parseSubDocs(parentDocs.value.map { MongoPolyCompleteDocument(it) }, subSegment.name)
            return TimedQueryValue(allSubDocs.map { subDoc ->
                subDoc.key to subDoc.value.filter { doc ->
                    checkCondition(
                        doc.doc,
                        subSegment.condition
                    )
                }
            }.toMap(), parentDocs.duration, "list $mongoParentCollection with $condition")
        }
    }

    private fun fetchConnectionSegment(
        segment: QuerySegment.Connection,
        startCollectionIds: Set<UUID>?
    ): TimedQueryValue<Map<MongoPolyConnection, MongoPolyCompleteDocument>> {
        val filters = mutableListOf<Bson>()
        if (segment.collectionCondition != null) {
            filters.add(conditionToFilter(segment.collectionCondition))
        }
        if (segment.connectionCondition != null) {
            filters.add(
                Filters.elemMatch(
                    "_con_${segment.connectionName}",
                    conditionToFilter(segment.connectionCondition, "_rel.")
                )
            )
        }
        if (startCollectionIds != null) {
            filters.add(
                Filters.elemMatch(
                    "_con_${segment.connectionName}",
                    Filters.`in`("_doc._id", startCollectionIds)
                )
            )
        }
        val collection = mongoDatabase.getCollection(segment.collectionName)
        val collectionDocs = measureTimedValue {
            collection
                .find(if (filters.isNotEmpty()) Filters.and(filters) else Filters.empty()).toList()
        }
        val result = buildMap {
            collectionDocs.value.forEach { parentDoc ->
                val relations = (parentDoc["_con_${segment.connectionName}"] as List<*>).filterIsInstance<Document>()
                relations.filter {
                    startCollectionIds == null || ((it["_doc"] as Document)["_id"] as UUID) in startCollectionIds
                }.filter {
                    checkCondition(it["_rel"] as Document, segment.connectionCondition)
                }
                    .forEach {
                        put(MongoPolyConnection(it), MongoPolyCompleteDocument(parentDoc))
                    }
            }
        }
        return TimedQueryValue(result, collectionDocs.duration, "list $collection with $filters")
    }

    private fun parseSubDocs(
        parentDocs: List<MongoPolyCompleteDocument>,
        subCollectionName: String,
    ): Map<MongoPolyDocument, List<MongoPolyDocument>> {
        return parentDocs.associate { it to it.getSubCollectionDocuments(subCollectionName) }
    }

    private fun takeResultFields(
        documents: Map<String, MongoPolyData>,
        onlys: List<Pair<String, List<String>?>>
    ): PolyData {
        return buildMap {
            for (only in onlys) {
                val (segment, fields) = only
                val segmentDoc = documents[segment]

                if (fields == null) {
                    putAll(segmentDoc?.filteredFieldEntries()?.toMap()?.mapKeys { "${segment}.${it.key}" } ?: continue)
                } else {
                    for (field in fields) {
                        put(
                            "${segment}.${field}",
                            segmentDoc?.getField(field)
                        )
                    }
                }
            }
        }
    }

    private fun conditionToFilter(condition: Condition?, prefix: String = ""): Bson {
        return when (condition) {
            is Condition.Comparison.Equals -> Filters.eq(
                "${prefix}${condition.field}",
                condition.value
            )

            is Condition.Comparison.LessThan -> Filters.lt(
                "${prefix}${condition.field}",
                condition.value
            )

            is Condition.Comparison.GreaterThan -> Filters.gt(
                "${prefix}${condition.field}",
                condition.value
            )

            is Condition.Logic.And -> Filters.and(conditionToFilter(condition.left), conditionToFilter(condition.right))
            is Condition.Logic.Or -> Filters.or(conditionToFilter(condition.left), conditionToFilter(condition.right))
            is Condition.Not -> Filters.not(conditionToFilter(condition.condition))
            is Condition.In -> Filters.`in`(condition.field, condition.list)
            null -> Filters.empty()
        }
    }

    private fun checkCondition(document: Map<String, Any?>, condition: Condition?): Boolean {
        return when (condition) {
            null -> true
            is Condition.Comparison.Equals -> document[condition.field] == condition.value
            is Condition.Comparison -> {
                when (val compValue = document[condition.field]) {
                    is Number -> when (condition) {
                        is Condition.Comparison.LessThan -> (compValue.toDouble() < condition.value.toDouble())
                        is Condition.Comparison.GreaterThan -> (compValue.toDouble() > condition.value.toDouble())
                    }

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
            is Condition.In -> document[condition.field] in condition.list
        }
    }

    /*override fun count(
        path: GetQuery,
        terminal: PolyTerminal.Count
    ): PolyResultData.Count {
        TODO("Not yet implemented")
    }*/

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
        return doc[name]
    }

    var cachedEntries: List<Pair<String, Any?>>? = null

    fun filteredFieldEntries(): List<Pair<String, Any?>> {
        if (cachedEntries == null) {
            cachedEntries = doc.entries.filter { !it.key.startsWith("_") || it.key == "_id" }
                .map { it.key to it.value }
        }
        return cachedEntries!!
    }
}

private abstract class MongoPolyDocument(doc: Document) : MongoPolyData(doc) {
    var storedId: UUID? = null
    fun id(): UUID {
        if (storedId == null) {
            storedId = doc["_id"] as UUID
        }
        return storedId!!
    }

    abstract fun getSubCollectionIds(name: String): List<UUID>
    abstract fun getConnectedIds(name: String): List<UUID>
}


private class MongoPolyCompleteDocument(doc: Document) : MongoPolyDocument(doc) {
    fun getSubCollectionDocuments(name: String): List<MongoPolySubDocument> {
        val subCollection = doc["_sub_${name}"] ?: return emptyList()
        check(subCollection is List<*>) { "sub collection $name does not exist on ${id()}" }
        return subCollection.filterIsInstance<Document>().map { MongoPolySubDocument(it) }
    }

    override fun getSubCollectionIds(name: String): List<UUID> {
        return getSubCollectionDocuments(name).map { it.id() }
    }

    fun getConnectionDocuments(name: String): List<MongoPolyConnection> {
        val subCollection = doc["_con_${name}"] ?: return emptyList()
        check(subCollection is List<*>) { "connection $name does not exist on ${id()}" }
        return subCollection.filterIsInstance<Document>().map { MongoPolyConnection(it) }
    }

    override fun getConnectedIds(name: String): List<UUID> {
        return getConnectionDocuments(name).map { it.getSubDoc().id() }
    }
}

private class MongoPolySubDocument(doc: Document) : MongoPolyDocument(doc) {
    override fun getSubCollectionIds(name: String): List<UUID> {
        val subCollection = doc["_sub_${name}"] ?: return emptyList()
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
        val connection = doc["_con_${name}"] ?: return emptyList()
        check(connection is List<*>) { "connection $name does not exist on ${id()}" }
        if (connection.isEmpty()) {
            return emptyList()
        }
        return if (connection.first()!! is UUID) {
            connection.filterIsInstance<UUID>()
        } else {
            connection.filterIsInstance<Document>().map { (it["_doc"] as Document)["_id"] as UUID }
        }
    }
}

private class MongoPolyConnection(doc: Document) : MongoPolyObject(doc) {
    fun getSubDoc(): MongoPolySubDocument {
        return MongoPolySubDocument(doc["_doc"] as Document)
    }

    fun getConnectionData(): MongoPolyData {
        return MongoPolyData(doc["_rel"] as Document)
    }
}

data class TimedQueryValue<T>(
    val value: T,
    val duration: Duration,
    val executedQuery: String
)