package benchmark.regression

import benchmark.BenchFilterType
import benchmark.Benchmark
import benchmark.Benchmark.faker
import benchmark.MeasurementPhase
import ch.flavianz.query.GetQueryBuilder
import ch.flavianz.query.get
import core.DatabaseManager
import driver.DriverManager
import driver.DriverManager.mongoDriver
import driver.DriverManager.neo4jDriver
import driver.DriverManager.postgresDriver
import driver.TimedDriverResult
import model.ConnectionModel
import model.DataType
import model.PolyData
import model.PolySchema
import query.Condition
import query.DriverType
import query.GetQuery
import query.QueryPath
import query.QuerySegment
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.text.get
import kotlin.time.Duration

class BenchEnvironmentRegression {
    val userCollections = listOf(
        "users", "children", "grand_children", "grand2_children",
        "grand3_children", "grand4_children", "grand5_children"
    )
    val petCollections = listOf("pets", "pets_child", "pets_grand_child")

    val userSchema = mapOf(
        "name" to DataType.STRING,
        "age" to DataType.INT,
        "male" to DataType.BOOLEAN
    )

    fun generateUserDoc(): PolyData {
        return mapOf(
            "name" to faker.name().firstName(),
            "last" to faker.name().firstName(),
            "age" to faker.number().numberBetween(0, 100),
            "male" to faker.bool().bool(),
            "income" to faker.number().numberBetween(0, 100_000)
        )
    }

    val petSchema = mapOf(
        "animal" to DataType.STRING,
        "age" to DataType.INT,
        "male" to DataType.BOOLEAN
    )

    fun generatePetDoc(): PolyData {
        return mapOf(
            "animal" to faker.animal().species(),
            "name" to faker.animal().name(),
            "age" to faker.number().numberBetween(0, 20),
            "male" to faker.bool().bool(),
            "legs" to faker.number().numberBetween(0, 9)
        )
    }

    val connectionSchema = mapOf(
        "since" to DataType.INT,
        "likes" to DataType.BOOLEAN,
        "nickname" to DataType.STRING
    )

    fun generateConnectionData(): PolyData {
        return mapOf(
            "nickname" to faker.animal().name(),
            "since" to faker.number().numberBetween(0, 20),
            "likes" to faker.bool().bool(),
        )
    }

    val collections = listOf(
        Triple(userCollections, userSchema, ::generateUserDoc),
        Triple(petCollections, petSchema, ::generatePetDoc)
    )

    // collection -> its own schema, used for sampleFieldValues lookups by name
    val schemaByCollection: Map<String, PolySchema> = buildMap {
        for (name in userCollections) put(name, userSchema)
        for (name in petCollections) put(name, petSchema)
    }

    // every (userCollection, petCollection) connection name, and its endpoint pair -
    // used both for insertion and for connection-field sampling
    val connectionEndpoints: Map<String, Pair<String, String>> = buildMap {
        for (u in userCollections) for (p in petCollections) put("${u}_owns_$p", u to p)
    }

    val collectionSizes = listOf(100/*, 500, 1000, 3000, 9000, 15000*/)

    val ids = mutableMapOf<String, MutableList<UUID>>()

    // collectionName -> (childId -> parentId). Populated during insertion, used to sample
    // structurally VALID leaf-to-root ID chains for GetDocByID/IdInList filters - without
    // this, independently sampling an id per collection level produces combinations where
    // the "child" isn't actually a descendant of the "parent" id chosen at another level,
    // which is why those queries were returning empty results despite both ids existing.
    val parentOf = mutableMapOf<String, MutableMap<UUID, UUID?>>()

    // parentId -> list of its children's ids (across whichever collection they belong to -
    // within one stack there's only ever one child collection per level, so no ambiguity).
    // Needed to walk a Kinder chain DOWNWARD from an already-bound anchor id; parentOf only
    // supports walking upward.
    val childrenOf = mutableMapOf<UUID, MutableList<UUID>>()

    // connectionName -> list of (nearOrderId=userSideId, farOrderId=petSideId, edgeData) for
    // every actually inserted edge. Same problem as parentOf, one level removed: independently
    // sampling a users._id and a pets._id (or an edge property value) produces combinations
    // that are real-but-unrelated almost every time. Storing the actual edge DATA too (not
    // just the endpoint ids) lets edge-property filters (e.g. "since") also be derived from a
    // real edge instead of sampled from the marginal distribution of ALL edges' values.
    val connectionEdges = mutableMapOf<String, MutableList<Triple<UUID, UUID, PolyData>>>()

    // populated once per collectionSize step in bench(); used by materializeQuery
    var fieldSamples: FieldSamples = emptyMap()

    // same idea as fieldSamples, but for connections' OWN properties (since/likes/nickname),
    // sampled from real inserted connection data - not from a collection's documents
    var connectionFieldSamples: FieldSamples = emptyMap()

    // structural query-path skeletons, built once (they don't depend on collectionSize) -
    // conditions get attached later, per collectionSize step, via materializeQuery
    val queryPathMetadata: List<QueryPathDescriptor> by lazy { buildQueryPathMetadata() }

    fun bench() {
        DatabaseManager.dropAllConnections()
        DatabaseManager.dropAllCollections()

        // create all collections
        for ((collectionGroup, collectionSchema) in collections) {
            for ((i, collectionName) in collectionGroup.withIndex()) {
                DatabaseManager.createCollection(
                    collectionName,
                    collectionSchema,
                    if (i == 0) null else collectionGroup[i - 1]
                )
            }
        }
        // create a connection between each pair of collections
        for ((connectionName, endpoints) in connectionEndpoints) {
            DatabaseManager.createConnection(
                ConnectionModel(connectionName, endpoints.first, endpoints.second, connectionSchema)
            )
        }

        // insert all documents step for step
        var currentCollectionSize = 0
        for (collectionSize in collectionSizes) {
            println("collection size $collectionSize")

            // insert documents in collections, using EVEN (round-robin over a shuffled pool)
            // parent assignment rather than independent random picks. Independent random
            // picks from a small pool reliably create "hot" hub documents (pigeonhole/
            // birthday-paradox effect) that then compound multiplicatively with connection
            // fan-out on multi-hop queries - this bounds max fan-out per parent instead.
            for ((collectionGroup, _, docGenerator) in collections) {
                for ((i, collectionName) in collectionGroup.withIndex()) {
                    val currentIdList = ids[collectionName] ?: mutableListOf()
                    val parentIds = if (i == 0) emptyList() else ids[collectionGroup[i - 1]] ?: emptyList()
                    val newDocCount = collectionSize - currentCollectionSize
                    val parentAssignments = evenAssignment(parentIds, newDocCount, Benchmark.seed.asKotlinRandom())
                    repeat(newDocCount) { docIndex ->
                        val parentId = if (i == 0) null else parentAssignments[docIndex]
                        val newId = DatabaseManager.insertDocument(collectionName, docGenerator(), parentId)
                        currentIdList.add(newId)
                        parentOf.getOrPut(collectionName) { mutableMapOf() }[newId] = parentId
                        if (parentId != null) childrenOf.getOrPut(parentId) { mutableListOf() }.add(newId)
                    }
                    ids[collectionName] = currentIdList
                }
            }

            // insert connections, using EVEN assignment on BOTH sides, capped so total
            // connections per pair never exceeds collectionSize - "as many connections as
            // there are documents," not more. Combined with calibrated connection-segment
            // filtering below, this keeps result sizes controlled from two directions instead
            // of relying on raw graph density alone (which either starves narrow/deep queries
            // or floods wide/shallow ones, and independently-random assignment creates hot
            // hubs regardless of density).
            for ((connectionName, endpoints) in connectionEndpoints) {
                val (userCollection, petCollection) = endpoints
                val userUuids = ids[userCollection] ?: emptyList()
                val petUuids = ids[petCollection] ?: emptyList()
                if (userUuids.isEmpty() || petUuids.isEmpty()) continue

                val connectionCount = collectionSize.coerceAtMost(max(userUuids.size, petUuids.size))
                val userSide = evenAssignment(userUuids, connectionCount, Benchmark.seed.asKotlinRandom())
                val petSide = evenAssignment(petUuids, connectionCount, Benchmark.seed.asKotlinRandom())

                repeat(connectionCount) { i ->
                    val edgeData = generateConnectionData()
                    DatabaseManager.insertConnection(
                        connectionName,
                        userCollection, userSide[i],
                        petCollection, petSide[i], edgeData
                    )
                    connectionEdges.getOrPut(connectionName) { mutableListOf() }
                        .add(Triple(userSide[i], petSide[i], edgeData))
                }
            }

            // refresh field samples now that this collectionSize's documents/connections are
            // inserted - MUST happen after insertion, since sampling reads real data back out
            fieldSamples = (userCollections + petCollections).associateWith { name ->
                sampleFieldValues(name, schemaByCollection.getValue(name))
            }
            connectionFieldSamples = connectionEndpoints.mapValues { (connectionName, endpoints) ->
                sampleConnectionFieldValues(connectionName, endpoints.first, endpoints.second)
            }

            // -------------------------------------------------------------------------
            // build the actual benchmark query set for this collectionSize step
            // -------------------------------------------------------------------------
            val conditionQueries = buildList {
                for (tier in SelectivityTier.entries) {
                    for (filterType in BenchFilterType.entries.filter { it != BenchFilterType.None }) {
                        for (descriptor in queryPathMetadata) {
                            val tiers = assignFiltersForPath(descriptor, tier)
                            val query =
                                materializeQuery(descriptor, tiers, filterType, fieldSamples, connectionFieldSamples)
                            if (query != null) add(query)
                            // null means no compatible field/id existed for this
                            // (descriptor, filterType) combination at the chosen positions -
                            // skip rather than emit a broken/no-op query
                        }
                    }
                }
                // plus a no-filter baseline pass, only for shallow descriptors - avoids
                // combinatorial-fanout unfiltered deep queries dominating result sizes
                for (descriptor in queryPathMetadata.filter { it.size <= 2 }) {
                    add(
                        materializeQuery(
                            descriptor,
                            List(descriptor.size) { null },
                            BenchFilterType.None,
                            fieldSamples,
                            connectionFieldSamples
                        )!! // no filter needed -> always succeeds, safe to assert non-null
                    )
                }
            }

            // TODO: hand `conditionQueries` off to your existing per-driver timing/measurement
            // loop (DriverManager.benchmarkTake or equivalent), tagging each DurationMeasurement
            // row with collectionSize (= collectionSize here), depth (= query.path.size), and
            // the structural features (first_filtered_segment_index, requires_multi_query, etc.)
            // computed directly from `query.path` at measurement time.

            for (query in conditionQueries) {
                File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\bench\\result-size.csv").appendText(
                    "${query};${
                        parseRegressionBenchMeasurement(
                            DriverType.Postgres, 100, MeasurementPhase.Build, 1,
                            Duration.ZERO, query
                        )
                    };\n"
                )
            }

            /*for ((index, query) in conditionQueries.withIndex()) {
                if (index % 100 == 0) println("$index of ${conditionQueries.size} queries complete")
                val results = mutableListOf<Set<PolyData>>()
                for (driver in listOf(
                    Pair(postgresDriver, DriverType.Postgres),
                    *//*Pair(mongoDriver, DriverType.Mongo),
                    Pair(neo4jDriver, DriverType.Neo4j)*//*
                )) {
                    try {
                        val result: TimedDriverResult<List<PolyData>> = driver.first!!.get(query)
                        results.add(result.data.toSet())
                        check(results.distinct().size == 1) {
                            "not all drivers returned the same result for query '${query}:\npostgres:(size ${
                                results.getOrNull(0)?.size
                            })${results.getOrNull(0)}\nmongo:(size ${results.getOrNull(1)?.size})${
                                results.getOrNull(1)
                            }\nneo4j:(size ${results.getOrNull(2)?.size})${results.getOrNull(2)}'"
                        }
                    } catch (e: Exception) {
                        println("error: $e")
                    }
                }
                File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\bench\\result-size.csv").appendText(
                    "${collectionSize};${results.first().size};$query\n"
                )
            }*/

            currentCollectionSize = collectionSize
        }
    }

    // -------------------------------------------------------------------------
    // structural query-path skeletons (no conditions yet)
    // -------------------------------------------------------------------------

    private fun buildQueryPathMetadata(): List<QueryPathDescriptor> {
        val collectionDepthPairs = buildList {
            for (i in 0..<7) {
                for (j in 0..<3) {
                    add(i to j)
                }
            }
        }

        val collectionDepthTriples = buildList {
            for (i in 0..<6) {
                for (j in 0..<3) {
                    for (k in 0..<(7 - i)) {
                        add(Triple(i, j, k))
                    }
                }
            }
        }

        val collectionDepthDeepTriple = buildList {
            for (i in 0..<6) {
                for (j in 0..<2) {
                    for (k in (i + 1)..<7) {
                        add(Triple(i, j, k))
                    }
                }
            }
        }

        return buildList {
            // simple sub collection queries
            for (i in 0..<7) {
                add(buildList {
                    for (k in 0..i) add(SegmentDescriptor(SegmentType.COLLECTION, userCollections[k]))
                })
            }
            // with one connection
            for (pair in collectionDepthPairs) {
                add(buildList {
                    for (i in 0..pair.first) add(SegmentDescriptor(SegmentType.COLLECTION, userCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, petCollections[0],
                            "${userCollections[pair.first]}_owns_${petCollections[0]}"
                        )
                    )
                    for (i in 1..pair.second) add(SegmentDescriptor(SegmentType.COLLECTION, petCollections[i]))
                })
            }
            // with one connection, reverse direction
            for (pair in collectionDepthPairs) {
                add(buildList {
                    for (i in 0..pair.second) add(SegmentDescriptor(SegmentType.COLLECTION, petCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, userCollections[0],
                            "${userCollections[0]}_owns_${petCollections[pair.second]}"
                        )
                    )
                    for (i in 1..pair.first) add(SegmentDescriptor(SegmentType.COLLECTION, userCollections[i]))
                })
            }
            // with two connections
            for (triple in collectionDepthTriples) {
                add(buildList {
                    for (i in 0..triple.first) add(SegmentDescriptor(SegmentType.COLLECTION, userCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, petCollections[0],
                            "${userCollections[triple.first]}_owns_${petCollections[0]}"
                        )
                    )
                    for (i in 1..triple.second) add(SegmentDescriptor(SegmentType.COLLECTION, petCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, userCollections[triple.first + 1],
                            "${userCollections[triple.first + 1]}_owns_${petCollections[triple.second]}"
                        )
                    )
                    for (i in (triple.first + 2)..triple.third) add(
                        SegmentDescriptor(SegmentType.COLLECTION, userCollections[i])
                    )
                })
            }
            // with three connections
            for (deepTriple in collectionDepthDeepTriple) {
                add(buildList {
                    for (i in 0..deepTriple.first) add(SegmentDescriptor(SegmentType.COLLECTION, userCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, petCollections[0],
                            "${userCollections[deepTriple.first]}_owns_${petCollections[0]}"
                        )
                    )
                    for (i in 1..deepTriple.second) add(SegmentDescriptor(SegmentType.COLLECTION, petCollections[i]))
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, userCollections[deepTriple.first + 1],
                            "${userCollections[deepTriple.first + 1]}_owns_${petCollections[deepTriple.second]}"
                        )
                    )
                    for (i in (deepTriple.first + 2)..deepTriple.third) add(
                        SegmentDescriptor(SegmentType.COLLECTION, userCollections[i])
                    )
                    add(
                        SegmentDescriptor(
                            SegmentType.CONNECTION, petCollections[deepTriple.second + 1],
                            "${userCollections[deepTriple.third]}_owns_${petCollections[deepTriple.second + 1]}"
                        )
                    )
                    for (i in (deepTriple.second + 2)..<3) add(
                        SegmentDescriptor(SegmentType.COLLECTION, petCollections[i])
                    )
                })
            }
        }
    }

    // -------------------------------------------------------------------------
    // even/bounded assignment - prevents hot-hub documents from independent random picks
    // -------------------------------------------------------------------------

    /**
     * Assigns `count` items drawn from `pool`, cycling through a single shuffled copy of the
     * pool round-robin rather than drawing independently at random each time. Independent
     * random draws from a small pool reliably produce a few massively over-represented items
     * (pigeonhole/birthday-paradox effect); round-robin bounds every pool item's usage count to
     * at most ceil(count / pool.size), eliminating hot hubs entirely rather than just reducing
     * their average likelihood.
     */
    private fun <T> evenAssignment(pool: List<T>, count: Int, rng: Random): List<T> {
        if (pool.isEmpty() || count == 0) return emptyList()
        val shuffledPool = pool.shuffled(rng)
        return List(count) { i -> shuffledPool[i % shuffledPool.size] }
    }

    // -------------------------------------------------------------------------
    // real-path sampling: the general fix
    // -------------------------------------------------------------------------
    //
    // Every filter value used anywhere in a query - whether an id or a field value, whether on
    // a Collection segment or a Connection's edge/far-node - must come from data that actually
    // lies on ONE real, mutually consistent path through the database. Sampling each segment's
    // filter independently from its own marginal distribution (e.g. "any age that exists in
    // children" + separately "any name that exists in grand_children") produces combinations
    // that are individually real but essentially never jointly satisfiable once branching
    // factor is low, since nothing guarantees the age-79 child you picked has a grandchild
    // named Ethelyn. sampleRealPath walks the descriptor once, picking one real id at every
    // position (via parentOf/childrenOf for Kinder hops, via connectionEdges for Connection
    // hops), so every filter derived from it is guaranteed jointly satisfiable by construction.

    data class RealizedNode(val id: UUID, val edgeData: PolyData? = null)

    private fun sampleRealPath(descriptor: QueryPathDescriptor): List<RealizedNode>? {
        val path = mutableListOf<RealizedNode>()
        var currentId: UUID? = null

        for (segment in descriptor) {
            when (segment.type) {
                SegmentType.COLLECTION -> {
                    // consecutive Collection segments are parent/child by construction
                    // (see buildQueryPathMetadata) - move to a real child of currentId, or
                    // pick a fresh random root if this is the first segment of the whole path
                    val nextId = if (currentId == null) {
                        ids[segment.collectionName]?.randomOrNull(Benchmark.seed.asKotlinRandom())
                    } else {
                        childrenOf[currentId]?.randomOrNull(Benchmark.seed.asKotlinRandom())
                    }
                    nextId ?: return null
                    path.add(RealizedNode(nextId))
                    currentId = nextId
                }

                SegmentType.CONNECTION -> {
                    val connectionName = segment.connectionName!!
                    val edges = connectionEdges[connectionName]
                    if (edges.isNullOrEmpty()) return null

                    val (userCollectionName, _) = connectionEndpoints.getValue(connectionName)
                    val farIsUserSide = segment.collectionName == userCollectionName
                    // normalize every stored (userId, petId, data) edge to (nearId, farId, data)
                    // given this query's actual traversal direction
                    val normalized = edges.map { (userId, petId, data) ->
                        if (farIsUserSide) Triple(petId, userId, data) else Triple(userId, petId, data)
                    }
                    val candidates = if (currentId != null) normalized.filter { it.first == currentId } else normalized
                    val chosen = candidates.randomOrNull(Benchmark.seed.asKotlinRandom()) ?: return null

                    path.add(RealizedNode(chosen.second, chosen.third))
                    currentId = chosen.second
                }
            }
        }

        return path
    }

    /** Fetches real field values for a batch of ids in one collection, keyed by id. */
    private fun fetchFieldsByIds(collectionName: String, targetIds: Set<UUID>): Map<UUID, Map<String, Any?>> {
        if (targetIds.isEmpty()) return emptyMap()
        val query = get {
            collection(collectionName, Condition.In("_id", targetIds))
        }
        val result = try {
            DriverManager.postgresDriver!!.get(query)
        } catch (e: Exception) {
            return emptyMap()
        }
        return result.data.mapNotNull { doc ->
            val stripped = doc.mapKeys { it.key.substringAfter(".") }
            (stripped["_id"] as? UUID)?.let { it to stripped }
        }.toMap()
    }

    // -------------------------------------------------------------------------
    // filter assignment
    // -------------------------------------------------------------------------

    /**
     * Decides which segments in a path receive a filter, and at what selectivity tier, so the
     * COMBINED result size across the whole path stays reasonable.
     *
     * IMPORTANT: unfiltered COLLECTION segments compound MULTIPLICATIVELY, same as unfiltered
     * connection segments - each unfiltered Kinder hop multiplies the candidate set by that
     * level's real branching factor. The original design here assumed collection segments'
     * unfiltered cost was merely additive (bounded by collection_size), which is true for
     * driver TIME cost but false for RESULT SIZE: real generated queries with 3-5 unfiltered
     * collection segments at depth 7-9 produced results in the hundreds of thousands, since
     * each unfiltered hop's branching factor multiplies against all the others. So: any path
     * deeper than 2 segments filters EVERY segment (collection or connection alike). Only
     * shallow paths (depth <= 2) keep some genuinely unfiltered coverage in the dataset.
     */
    fun assignFiltersForPath(
        descriptor: QueryPathDescriptor,
        targetCombinedSelectivity: SelectivityTier
    ): List<SelectivityTier?> {
        val depth = descriptor.size
        val tiers = MutableList<SelectivityTier?>(depth) { null }

        if (depth <= 2) {
            // shallow: preserve some genuinely unfiltered coverage in the dataset, since a
            // 1-2 hop unfiltered path can't compound into a runaway result size
            for (i in descriptor.indices) {
                if (Benchmark.seed.asKotlinRandom().nextBoolean()) tiers[i] = targetCombinedSelectivity
            }
        } else {
            // deep: filter every segment, collection or connection - unfiltered hops compound
            // multiplicatively regardless of segment type once depth is more than trivial
            for (i in descriptor.indices) tiers[i] = targetCombinedSelectivity
        }

        return tiers
    }

    // -------------------------------------------------------------------------
    // condition construction - all derived from real sampled path(s), never independently
    // -------------------------------------------------------------------------

    private data class ChosenField(val name: String, val isEdgeField: Boolean, val dataType: DataType)

    /**
     * Materializes a full GetQuery from a structural descriptor + chosen tiers/filterType.
     *
     * Every filter is derived from one or more REAL sampled paths (sampleRealPath), not from
     * independent per-segment sampling - this is what guarantees the whole query is jointly
     * satisfiable: whatever value ends up in a filter at any position genuinely co-occurs,
     * along a real chain of parent/child/connection relationships, with whatever values are
     * used at every other filtered position in the same query.
     *
     * Returns null if no real path could be sampled, or if a required field/id wasn't
     * available - callers should skip these rather than benchmark a query that doesn't
     * reflect the intended filter configuration (or worse, is guaranteed empty).
     */
    fun materializeQuery(
        descriptor: QueryPathDescriptor,
        tiers: List<SelectivityTier?>,
        filterType: BenchFilterType,
        fieldSamples: FieldSamples,
        connectionFieldSamples: FieldSamples
    ): GetQuery? {
        if (filterType == BenchFilterType.None || tiers.all { it == null }) {
            return get {
                descriptor.forEach { segment ->
                    when (segment.type) {
                        SegmentType.COLLECTION -> collection(segment.collectionName, null, segment.only)
                        SegmentType.CONNECTION -> connection(
                            segment.connectionName!!,
                            segment.collectionName,
                            null,
                            null
                        )
                    }
                }
            }
        }

        val needsMultiplePaths = filterType == BenchFilterType.IdInList || filterType == BenchFilterType.ValueInList
        val pathSampleCount = if (needsMultiplePaths) 30 else 1
        val realPaths = (1..pathSampleCount).mapNotNull { sampleRealPath(descriptor) }
        if (realPaths.isEmpty()) return null
        val primaryPath = realPaths.first()

        // pick one compatible field per filtered segment up front, reused consistently across
        // all sampled paths - not needed for id-based filter types
        val chosenFieldByIndex: Map<Int, ChosenField> =
            if (filterType == BenchFilterType.GetDocByID || filterType == BenchFilterType.IdInList) {
                emptyMap()
            } else {
                buildMap {
                    descriptor.forEachIndexed { idx, segment ->
                        if (tiers[idx] == null) return@forEachIndexed
                        val useEdge =
                            segment.type == SegmentType.CONNECTION && Benchmark.seed.asKotlinRandom().nextBoolean()
                        val samples =
                            if (useEdge) connectionFieldSamples[segment.connectionName] else fieldSamples[segment.collectionName]
                        val compatible = samples?.filter { compatibleWith(it.type, filterType) }.orEmpty()
                        val field = compatible.randomOrNull(Benchmark.seed.asKotlinRandom())
                        if (field != null) {
                            put(idx, ChosenField(field.field, useEdge, field.type))
                        } else if (useEdge) {
                            // no compatible edge field - fall back to far-node field instead
                            val fallback = fieldSamples[segment.collectionName]
                                ?.filter { compatibleWith(it.type, filterType) }
                                ?.randomOrNull(Benchmark.seed.asKotlinRandom())
                            if (fallback != null) put(idx, ChosenField(fallback.field, false, fallback.type))
                        }
                    }
                }
            }

        // batch-fetch real field values for every (collection, id) touched by any sampled
        // path, for whichever segments need a far-node/collection field (not needed for pure
        // edge-property filters, which already have their value in RealizedNode.edgeData)
        val idsNeedingFields = mutableMapOf<String, MutableSet<UUID>>()
        chosenFieldByIndex.forEach { (idx, field) ->
            if (!field.isEdgeField) {
                val collectionName = descriptor[idx].collectionName
                for (path in realPaths) {
                    idsNeedingFields.getOrPut(collectionName) { mutableSetOf() }.add(path[idx].id)
                }
            }
        }
        val fetchedFields: Map<String, Map<UUID, Map<String, Any?>>> =
            idsNeedingFields.mapValues { (collectionName, idSet) -> fetchFieldsByIds(collectionName, idSet) }

        fun realValueAt(pathIndex: Int, path: List<RealizedNode>, field: ChosenField): Any? {
            return if (field.isEdgeField) {
                path[pathIndex].edgeData?.get(field.name)
            } else {
                val collectionName = descriptor[pathIndex].collectionName
                fetchedFields[collectionName]?.get(path[pathIndex].id)?.get(field.name)
            }
        }

        data class ResolvedCondition(val collectionCondition: Condition?, val connectionCondition: Condition?)

        val resolved = descriptor.mapIndexed { idx, segment ->
            val tier = tiers[idx] ?: return@mapIndexed ResolvedCondition(null, null)

            val condition: Condition = when (filterType) {
                BenchFilterType.GetDocByID -> Condition.Comparison.Equals("_id", primaryPath[idx].id)

                BenchFilterType.IdInList -> {
                    val idSet = realPaths.map { it[idx].id }.distinct()
                    val n = (tier.targetFraction * idSet.size).toInt().coerceIn(1, idSet.size)
                    val shuffled = idSet.shuffled(Benchmark.seed.asKotlinRandom())
                    // guarantee the primary path's own id is always included, so at least
                    // that one fully-consistent combination is always a real match
                    val forced = setOf(primaryPath[idx].id)
                    Condition.In("_id", (forced + shuffled.take(n)).toSet())
                }

                BenchFilterType.Equality -> {
                    val field = chosenFieldByIndex[idx] ?: return@mapIndexed ResolvedCondition(null, null)
                    val value = realValueAt(idx, primaryPath, field) ?: return@mapIndexed ResolvedCondition(null, null)
                    Condition.Comparison.Equals(field.name, value)
                }

                BenchFilterType.NumberRange -> {
                    val field = chosenFieldByIndex[idx] ?: return@mapIndexed ResolvedCondition(null, null)
                    val rawValue = realValueAt(idx, primaryPath, field) as? Number
                        ?: return@mapIndexed ResolvedCondition(null, null)
                    val value = rawValue.toDouble()
                    // Percentile-based threshold (bounded within the field's real observed
                    // range, unlike a raw value-minus-fraction*range subtraction which can
                    // overshoot past the field's actual minimum and become a no-op filter -
                    // e.g. WIDE (0.6) on a small real value previously produced thresholds
                    // like -12.7 on an age field, matching the ENTIRE collection instead of
                    // the intended ~60%). If the real sampled value doesn't naturally clear
                    // the percentile threshold, lower the threshold just enough to admit it -
                    // this guarantees a match without ever leaving the field's real range.
                    val marginalSamples = (if (field.isEdgeField) connectionFieldSamples[descriptor[idx].connectionName]
                    else fieldSamples[descriptor[idx].collectionName])
                        ?.find { it.field == field.name }?.values?.map { (it as Number).toDouble() }?.sorted()
                    val percentileThreshold = marginalSamples?.let { sorted ->
                        val percentileIndex =
                            ((1.0 - tier.targetFraction) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
                        sorted[percentileIndex]
                    } ?: (value - 1.0)
                    val threshold = minOf(percentileThreshold, value - 0.0001)
                    Condition.Comparison.GreaterThan(field.name, threshold)
                }

                BenchFilterType.ValueInList -> {
                    val field = chosenFieldByIndex[idx] ?: return@mapIndexed ResolvedCondition(null, null)
                    val valuesPerPath = realPaths.mapNotNull { path -> realValueAt(idx, path, field) }
                    val distinctValues = valuesPerPath.distinct()
                    if (distinctValues.isEmpty()) return@mapIndexed ResolvedCondition(null, null)
                    val n = (tier.targetFraction * distinctValues.size).toInt().coerceIn(1, distinctValues.size)
                    val primaryValue = realValueAt(idx, primaryPath, field)
                    val forced = if (primaryValue != null) setOf(primaryValue) else emptySet()
                    Condition.In(
                        field.name,
                        (forced + distinctValues.shuffled(Benchmark.seed.asKotlinRandom()).take(n)).toSet()
                    )
                }

                BenchFilterType.None -> return@mapIndexed ResolvedCondition(null, null)
            }

            val isEdgeCondition = chosenFieldByIndex[idx]?.isEdgeField == true
            if (isEdgeCondition) ResolvedCondition(null, condition) else ResolvedCondition(condition, null)
        }

        return get {
            descriptor.zip(resolved).forEach { (segment, condition) ->
                when (segment.type) {
                    SegmentType.COLLECTION -> collection(
                        segment.collectionName,
                        condition.collectionCondition,
                        segment.only
                    )

                    SegmentType.CONNECTION -> connection(
                        segment.connectionName!!, segment.collectionName,
                        connectionCondition = condition.connectionCondition,
                        collectionCondition = condition.collectionCondition
                    )
                }
            }
        }
    }


    fun compatibleWith(dataType: DataType, filterType: BenchFilterType): Boolean {
        return when (filterType) {
            BenchFilterType.ValueInList -> dataType != DataType.UUID
            BenchFilterType.Equality -> dataType != DataType.UUID
            BenchFilterType.IdInList -> dataType == DataType.UUID
            BenchFilterType.NumberRange -> dataType == DataType.INT || dataType == DataType.FLOAT
            BenchFilterType.GetDocByID -> dataType == DataType.UUID
            BenchFilterType.None -> true
        }
    }

    fun sampleFieldValues(collectionName: String, schema: PolySchema, sampleSize: Int = 500): List<FieldSample> {
        val docs = sampleDocuments(collectionName, sampleSize)
        return schema.entries
            .filter { it.value != DataType.BOOLEAN }
            .map { (field, type) -> FieldSample(field, type, docs.mapNotNull { it[field] }) }
            .filter { it.values.isNotEmpty() } // drop fields we couldn't sample any values for
    }

    /**
     * Samples real values for a CONNECTION's own properties (e.g. "since", "likes"), analogous
     * to sampleFieldValues but reading the connection's edge data rather than a collection's
     * documents. Enables calibrated filtering directly on the connection's own fields
     * (connectionCondition), not just on the far node's fields (collectionCondition).
     */
    fun sampleConnectionFieldValues(
        connectionName: String,
        ownerCollection: String,
        farCollection: String,
        sampleSize: Int = 300
    ): List<FieldSample> {
        val ownerIds = ids[ownerCollection]
        if (ownerIds.isNullOrEmpty()) return emptyList()
        val sampledOwnerIds = ownerIds.shuffled(Benchmark.seed.asKotlinRandom()).take(sampleSize)

        val schema = connectionSchema
        val fieldNames = schema.keys.toList()

        val query = get {
            collection(ownerCollection, Condition.In("_id", sampledOwnerIds.toSet()))
            connection(connectionName, farCollection, connectionOnly = fieldNames)
        }

        val result = try {
            DriverManager.postgresDriver!!.get(query)
        } catch (e: Exception) {
            return emptyList()
        }

        val prefix = "$connectionName."
        val docs = result.data.map { doc ->
            doc.entries.filter { it.key.startsWith(prefix) }
                .associate { it.key.removePrefix(prefix) to it.value }
        }

        return schema.entries
            .filter { it.value != DataType.BOOLEAN }
            .map { (field, type) -> FieldSample(field, type, docs.mapNotNull { it[field] }) }
            .filter { it.values.isNotEmpty() }
    }

    fun sampleDocuments(collectionName: String, sampleSize: Int = 500): List<Map<String, Any?>> {
        val candidateIds = ids[collectionName] ?: return emptyList()
        if (candidateIds.isEmpty()) return emptyList()
        val sampledIds = candidateIds.shuffled(Benchmark.seed.asKotlinRandom()).take(sampleSize)

        val query = get {
            collection(collectionName, Condition.In("_id", sampledIds.toSet()))
        }
        // run against ANY single driver - the underlying data is identical across all three,
        // so it doesn't matter which one answers this (not a benchmarked measurement)
        val result = DriverManager.postgresDriver!!.get(query)
        return result.data.map { doc -> doc.mapKeys { it.key.substringAfter(".") } }
    }
}

enum class SelectivityTier(val targetFraction: Double) {
    NARROW(0.05), MEDIUM(0.25), WIDE(0.6)
}

enum class SegmentType { COLLECTION, CONNECTION }

data class SegmentDescriptor(
    val type: SegmentType,
    val collectionName: String,      // for CONNECTION: the FAR collection being joined to
    val connectionName: String? = null, // only set when type == CONNECTION
    val only: List<String>? = null      // optional, if you want to vary doc/only per segment too
)

typealias QueryPathDescriptor = List<SegmentDescriptor>

data class FieldSample(val field: String, val type: DataType, val values: List<Any>)

// name -> sampled field values for that collection/connection, refreshed at each collectionSize step
typealias FieldSamples = Map<String, List<FieldSample>>