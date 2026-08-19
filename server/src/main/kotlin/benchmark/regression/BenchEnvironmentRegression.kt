package benchmark.regression

import benchmark.BenchFilterType
import benchmark.Benchmark
import benchmark.Benchmark.faker
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
import java.util.UUID
import kotlin.random.asKotlinRandom
import kotlin.text.get

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

    val collectionSizes = listOf(100, 500, 1000, 3000, 9000, 15000)

    val ids = mutableMapOf<String, MutableList<UUID>>()

    // populated once per collectionSize step in bench(); used by materializeQuery
    var fieldSamples: FieldSamples = emptyMap()

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
        for (userCollection in userCollections) {
            for (petCollection in petCollections) {
                DatabaseManager.createConnection(
                    ConnectionModel(
                        "${userCollection}_owns_$petCollection",
                        userCollection,
                        petCollection,
                        connectionSchema
                    )
                )
            }
        }

        // insert all documents step for step
        var currentCollectionSize = 0
        for (collectionSize in collectionSizes) {
            // insert documents in collections
            for ((collectionGroup, _, docGenerator) in collections) {
                for ((i, collectionName) in collectionGroup.withIndex()) {
                    val currentIdList = ids[collectionName] ?: mutableListOf()
                    val parentIds = if (i == 0) emptyList() else ids[collectionGroup[i - 1]] ?: emptyList()
                    repeat(collectionSize - currentCollectionSize) {
                        currentIdList.add(
                            DatabaseManager.insertDocument(
                                collectionName,
                                docGenerator(),
                                if (i == 0) null else parentIds.random(Benchmark.seed.asKotlinRandom())
                            )
                        )
                    }
                    ids[collectionName] = currentIdList
                }
            }

            // insert connections between each pair of collections
            for (userCollection in userCollections) {
                for (petCollection in petCollections) {
                    val userUuids = ids[userCollection] ?: emptyList()
                    val petUuids = ids[petCollection] ?: emptyList()
                    repeat((collectionSize - currentCollectionSize) / 10) {
                        DatabaseManager.insertConnection(
                            "${userCollection}_owns_$petCollection",
                            userCollection, userUuids.random(Benchmark.seed.asKotlinRandom()),
                            petCollection, petUuids.random(Benchmark.seed.asKotlinRandom()), generateConnectionData()
                        )
                    }
                }
            }

            // refresh field samples now that this collectionSize's documents are inserted -
            // MUST happen after insertion, since sampleDocuments reads real data back out
            fieldSamples = (userCollections + petCollections).associateWith { name ->
                sampleFieldValues(name, schemaByCollection.getValue(name))
            }

            // -------------------------------------------------------------------------
            // build the actual benchmark query set for this collectionSize step
            // -------------------------------------------------------------------------
            val conditionQueries = buildList {
                for (tier in SelectivityTier.entries) {
                    for (filterType in BenchFilterType.entries.filter { it != BenchFilterType.None }) {
                        for (descriptor in queryPathMetadata) {
                            val depth = descriptor.size
                            val tiers = assignFiltersForPath(depth, tier)
                            val query = materializeQuery(descriptor, tiers, filterType, fieldSamples)
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
                            fieldSamples
                        )!! // no filter needed -> always succeeds, safe to assert non-null
                    )
                }
            }

            // TODO: hand `conditionQueries` off to your existing per-driver timing/measurement
            // loop (DriverManager.benchmarkTake or equivalent), tagging each DurationMeasurement
            // row with collectionSize (= collectionSize here), depth (= query.path.size), and
            // the structural features (first_filtered_segment_index, requires_multi_query, etc.)
            // computed directly from `query.path` at measurement time.

            var zeroCount = 0
            var twoHundredCount = 0
            var fiveHundredCount = 0

            for ((index, query) in conditionQueries.withIndex()) {
                if (index % 100 == 0) println("${index} of ${conditionQueries.size} queries complete")
                val results = mutableListOf<Set<PolyData>>()
                for (i in 0..<2) {
                    for (driver in listOf(
                        Pair(postgresDriver, DriverType.Postgres),
                        Pair(mongoDriver, DriverType.Mongo),
                        Pair(neo4jDriver, DriverType.Neo4j)
                    )) {
                        lateinit var result: TimedDriverResult<List<PolyData>>
                        try {
                            result = driver.first!!.get(query)
                            if (i == 0) {
                                results.add(result.data.toSet())
                                check(results.distinct().size == 1) {
                                    "not all drivers returned the same result for query '${query}:\npostgres:(size ${
                                        results.getOrNull(
                                            0
                                        )?.size
                                    })${results.getOrNull(0)}\nmongo:(size ${results.getOrNull(1)?.size})${
                                        results.getOrNull(
                                            1
                                        )
                                    }\nneo4j:(size ${results.getOrNull(2)?.size})${results.getOrNull(2)}'"
                                }
                            }

                        } catch (e: Exception) {
                            println("error: $e")
                        }
                    }
                    if (i == 0) {
                        println("driver results equal")
                    }
                }
                if (results.first().isEmpty()) {
                    zeroCount++
                }
                if (results.first().size > 200) {
                    twoHundredCount++
                }
                if (results.first().size > 200) {
                    fiveHundredCount++
                }
            }

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
    // field/id sampling
    // -------------------------------------------------------------------------

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

    fun sampleFieldValues(collectionName: String, schema: PolySchema, sampleSize: Int = 500): List<FieldSample> {
        val docs = sampleDocuments(collectionName, sampleSize)
        return schema.entries
            .filter { it.value != DataType.BOOLEAN }
            .map { (field, type) -> FieldSample(field, type, docs.mapNotNull { it[field] }) }
            .filter { it.values.isNotEmpty() } // drop fields we couldn't sample any values for
    }

    // -------------------------------------------------------------------------
    // condition construction
    // -------------------------------------------------------------------------

    /**
     * Builds a condition for one segment, dispatching to id-based sampling (GetDocByID/IdInList,
     * drawn from `ids` - the real UUID population) vs. value-based sampling (everything else,
     * drawn from `fieldSamples` - real sampled field values). Returns null if no compatible
     * field/id data is available for this (collection, filterType) combination, so the caller
     * can skip emitting a broken query rather than crash.
     */
    private fun buildConditionForSegment(
        collectionName: String,
        tier: SelectivityTier,
        filterType: BenchFilterType,
        fieldSamples: FieldSamples
    ): Condition? {
        return when (filterType) {
            BenchFilterType.GetDocByID -> {
                val idList = ids[collectionName]
                if (idList.isNullOrEmpty()) return null
                Condition.Comparison.Equals("_id", idList.random(Benchmark.seed.asKotlinRandom()))
            }

            BenchFilterType.IdInList -> {
                val idList = ids[collectionName]
                if (idList.isNullOrEmpty()) return null
                val n = (tier.targetFraction * idList.size).toInt().coerceIn(1, idList.size)
                Condition.In("_id", idList.shuffled(Benchmark.seed.asKotlinRandom()).take(n).toSet())
            }

            BenchFilterType.None -> null

            else -> {
                val samples = fieldSamples[collectionName] ?: return null
                val sample = samples.filter { compatibleWith(it.type, filterType) }
                    .randomOrNull(Benchmark.seed.asKotlinRandom()) ?: return null
                buildCondition(sample, tier, filterType)
            }
        }
    }

    fun buildCondition(sample: FieldSample, tier: SelectivityTier, filterType: BenchFilterType): Condition {
        return when (filterType) {
            BenchFilterType.NumberRange -> {
                val sorted = sample.values.map { (it as Number).toDouble() }.sorted()
                // WIDE -> low threshold (matches most), NARROW -> high threshold (matches few)
                val percentileIndex = ((1.0 - tier.targetFraction) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
                Condition.Comparison.GreaterThan(sample.field, sorted[percentileIndex])
            }

            BenchFilterType.Equality -> {
                Condition.Comparison.Equals(sample.field, sample.values.random(Benchmark.seed.asKotlinRandom()))
            }

            BenchFilterType.ValueInList -> {
                val n = (tier.targetFraction * sample.values.size).toInt().coerceIn(1, sample.values.size)
                Condition.In(sample.field, sample.values.shuffled(Benchmark.seed.asKotlinRandom()).take(n).toSet())
            }

            BenchFilterType.GetDocByID, BenchFilterType.IdInList ->
                error("$filterType must be handled via buildConditionForSegment, not buildCondition")

            BenchFilterType.None -> error("None should never reach buildCondition")
        }
    }

    /**
     * Decides which segments in a path receive a filter, and at what selectivity tier, so the
     * COMBINED selectivity across the whole path stays reasonable. Filtering every segment of a
     * deep path independently would compound multiplicatively toward near-empty results (e.g.
     * 0.25^4 =~ 0.4%), so deeper paths get fewer, deliberately-placed filters instead of one per
     * segment. Positions are randomized so first_filtered_segment_index varies across the
     * generated dataset rather than always landing at a fixed position.
     */
    fun assignFiltersForPath(depth: Int, targetCombinedSelectivity: SelectivityTier): List<SelectivityTier?> {
        val filterCount = when {
            depth <= 2 -> depth
            depth <= 4 -> 2
            else -> 1
        }.coerceAtMost(depth)
        val filterPositions = (0..<depth).shuffled(Benchmark.seed.asKotlinRandom()).take(filterCount).toSet()
        return (0..<depth).map { if (it in filterPositions) targetCombinedSelectivity else null }
    }

    /**
     * Materializes a full GetQuery from a structural descriptor + chosen tiers/filterType.
     * Returns null (rather than a query with silently-missing filters) if any segment that was
     * assigned a tier couldn't get a compatible condition built - callers should skip these
     * rather than benchmark a query that doesn't reflect the intended filter configuration.
     */
    fun materializeQuery(
        descriptor: QueryPathDescriptor,
        tiers: List<SelectivityTier?>,
        filterType: BenchFilterType,
        fieldSamples: FieldSamples
    ): GetQuery? {
        val conditions = descriptor.zip(tiers).map { (segment, tier) ->
            if (tier == null) {
                null
            } else {
                buildConditionForSegment(segment.collectionName, tier, filterType, fieldSamples)
                    ?: return null // this segment was supposed to be filtered but couldn't be - abort
            }
        }

        return get {
            descriptor.zip(conditions).forEach { (segment, condition) ->
                when (segment.type) {
                    SegmentType.COLLECTION -> collection(segment.collectionName, condition, segment.only)
                    SegmentType.CONNECTION -> connection(
                        segment.connectionName!!, segment.collectionName,
                        connectionCondition = null, collectionCondition = condition
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

// collectionName -> sampled field values for that collection, refreshed at each collectionSize step
typealias FieldSamples = Map<String, List<FieldSample>>