package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.query.and
import ch.flavianz.query.eq
import ch.flavianz.query.gt
import ch.flavianz.query.lt
import ch.flavianz.query.or
import ch.flavianz.query.get
import java.util.UUID
import kotlin.random.asKotlinRandom

interface BenchEnvironment {
    val runId: Int
    val collectionSize: Int
    fun init()
    fun cleanUp()
    fun bench(): List<DurationMeasurement>
}

class BenchEnvironmentSimpleCollection(override val runId: Int, override val collectionSize: Int) : BenchEnvironment {
    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()
    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        for (i in 0..<20) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 20),
                    "male" to false
                )
            )
        }
        for (i in 0..<10) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to true
                )
            )
        }
        for (i in 0..<(collectionSize - 20)) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(20, 80),
                    "male" to false
                )
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users")
    }

    override fun bench(): List<DurationMeasurement> {
        bench1()
        println("env simple collection bench 1 done")
        bench2()
        println("env simple collection bench 2 done")
        bench3()
        println("env simple collection bench 3 done")
        bench4()
        println("env simple collection bench 4 done")
        return durationMeasurements
    }

    private fun bench1() {
        if (collectionSize > 100) {
            return
        }
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "collection collect all",
                collectionSize,
                depth = 1,
                filterCount = 0,
                get {
                    collection("users")
                }
            )
        )
    }

    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "collection collect 1 filter",
                collectionSize,
                depth = 1,
                filterCount = 1,
                get {
                    collection("users", "age" gt 79)
                }
            )
        )
    }

    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "collection collect 2 filter",
                collectionSize,
                depth = 1,
                filterCount = 2,
                get {
                    collection("users", ("age" lt 80) and ("male" eq true))
                }
            )
        )
    }

    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "collection collect 3 filter",
                collectionSize,
                depth = 1,
                filterCount = 3,
                get {
                    collection("users", ("age" lt 80) and (("age" gt 59) and ("male" eq true)))
                }
            )
        )
    }
}


class BenchEnvironmentSubCollection(override val runId: Int, override val collectionSize: Int) : BenchEnvironment {
    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()
    override fun init() {

        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        DatabaseManager.createCollection(
            "children", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "users"
        )
        val ids = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                ), ids.random(Benchmark.seed.asKotlinRandom())
            )

        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }

    override fun bench(): List<DurationMeasurement> {
        bench1()
        println("env subcollection bench 1 done")
        bench2()
        println("env subcollection bench 2 done")
        bench3()
        println("env subcollection bench 3 done")
        bench4()
        println("env subcollection bench 4 done")
        bench5()
        println("env subcollection bench 5 done")
        bench6()
        println("env subcollection bench 6 done")
        return durationMeasurements
    }

    private fun bench1() {
        if (collectionSize > 100) {
            return
        }
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect all",
                collectionSize,
                depth = 2,
                filterCount = 0,
                get {
                    collection("users")
                    collection("children")
                }
            )
        )
    }

    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect 1 filter",
                collectionSize,
                depth = 2,
                filterCount = 1,
                get {
                    collection("users")
                    collection("children", "age" gt 79)
                }
            )
        )
    }

    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect 2 filter",
                collectionSize,
                depth = 2,
                filterCount = 2,
                get {
                    collection("users")
                    collection("children", ("age" lt 80) and ("male" eq true))
                }
            )
        )
    }

    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect 3 filter",
                collectionSize,
                depth = 2,
                filterCount = 3,
                get {
                    collection("users")
                    collection(
                        "children",
                        ("age" lt 80) and (("age" gt 59) or ("male" eq true))
                    )
                }
            )
        )
    }

    private fun bench5() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect 2 filter parent 1 filter",
                collectionSize,
                depth = 2,
                filterCount = 3,
                get {
                    collection("users", "age" gt 79)
                    collection("children", ("age" lt 80) and ("age" gt 59))
                }
            )
        )
    }

    private fun bench6() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "sub collection collect 2 filter parent 2 filter",
                collectionSize,
                depth = 2,
                filterCount = 4,
                get {
                    collection("users", ("age" lt 80) and ("age" gt 59))
                    collection("children", ("age" lt 80) and ("age" gt 59))
                }
            )
        )
    }
}

class BenchEnvironmentDeepSubCollection(override val runId: Int, override val collectionSize: Int) :
    BenchEnvironment {
    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()

    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        DatabaseManager.createCollection(
            "children", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "users"
        )
        DatabaseManager.createCollection(
            "grandchildren", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "children"
        )

        val userIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }

        val childIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            childIds.add(
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    ), userIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }

        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                ), childIds.random(Benchmark.seed.asKotlinRandom())
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }

    override fun bench(): List<DurationMeasurement> {
        bench1()
        println("env deep subcollection bench 1 done")
        bench2()
        println("env deep subcollection bench 2 done")
        bench3()
        println("env deep subcollection bench 3 done")
        bench4()
        println("env deep subcollection bench 4 done")
        return durationMeasurements
    }

    // 3-hop, unfiltered: isolates pure depth cost
    private fun bench1() {
        if (collectionSize > 100) {
            return
        }
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "deep sub collection collect all",
                collectionSize,
                depth = 3,
                filterCount = 0,
                get {
                    collection("users")
                    collection("children")
                    collection("grandchildren")
                }
            )
        )
    }

    // 3-hop, filter only on leaf level
    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "deep sub collection leaf filter",
                collectionSize,
                depth = 3,
                filterCount = 1,
                get {
                    collection("users")
                    collection("children")
                    collection("grandchildren", "age" gt 79)
                }
            )
        )
    }

    // 3-hop, filter at every level: worst case for cost compounding
    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "deep sub collection filter every level",
                collectionSize,
                depth = 3,
                filterCount = 3,
                get {
                    collection("users", "age" lt 80)
                    collection("children", "male" eq true)
                    collection("grandchildren", "age" gt 59)
                }
            )
        )
    }

    // 2-hop query (users -> children) run inside the 3-level schema, so you can compare
    // depth=2 vs depth=3 while collection_size and schema shape are otherwise identical
    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "deep schema shallow query 2 hop",
                collectionSize,
                depth = 2,
                filterCount = 1,
                get {
                    collection("users")
                    collection("children", "age" gt 79)
                }
            )
        )
    }
}

class BenchEnvironmentConnection(override val runId: Int, override val collectionSize: Int) : BenchEnvironment {
    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()

    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        DatabaseManager.createCollection(
            "hobbies", mapOf(
                "name" to DataType.STRING
            )
        )
        DatabaseManager.createConnection(
            ConnectionModel(
                "practices", "users", "hobbies",
                mapOf(
                    "years_active" to DataType.INT
                ),
            )
        )

        val userIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }

        val hobbyIds = mutableListOf<UUID>()
        for (i in 0..<20) {
            hobbyIds.add(
                DatabaseManager.insertDocument(
                    "hobbies", mapOf(
                        "name" to faker.hobby().activity()
                    )
                )
            )
        }

        val rng = Benchmark.seed.asKotlinRandom()
        for (userId in userIds) {
            val connectionCount = rng.nextInt(1, 4)
            repeat(connectionCount) {
                DatabaseManager.insertConnection(
                    "practices",
                    "users",
                    userId, "hobbies",
                    hobbyIds.random(rng),
                    mapOf("years_active" to faker.number().numberBetween(0, 30))
                )
            }
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropConnection("practices")
        DatabaseManager.dropCollection("users", true)
        DatabaseManager.dropCollection("hobbies", true)
    }

    override fun bench(): List<DurationMeasurement> {
        bench1()
        println("env connection bench 1 done")
        bench2()
        println("env connection bench 2 done")
        bench3()
        println("env connection bench 3 done")
        bench4()
        println("env connection bench 4 done")
        return durationMeasurements
    }

    // unfiltered traversal across the connection: baseline n:n cost, no filter
    private fun bench1() {
        if (collectionSize > 100) {
            return
        }
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "connection collect all",
                collectionSize,
                depth = 1,
                filterCount = 0,
                get {
                    collection("users")
                    connection("practices", "hobbies")
                }
            )
        )
    }

    // filter on the connection's own property (years_active) - exercises edge-property filtering,
    // which is a distinct code path from filtering on node/document fields in most drivers
    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "connection filter on edge property",
                collectionSize,
                depth = 1,
                filterCount = 1,
                get {
                    collection("users")
                    connection("practices", "hobbies", connectionCondition = "years_active" gt 10)
                }
            )
        )
    }

    // filter on the far-side node (hobbies.name) reached through the connection
    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "connection filter on far node",
                collectionSize,
                depth = 1,
                filterCount = 1,
                get {
                    collection("users")
                    connection("practices", "hobbies", collectionCondition = "name" eq faker.hobby().activity())
                }
            )
        )
    }

    // filters on both the near node and the edge property simultaneously - combined cost
    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkGet(
                runId,
                queryShape = "connection filter near node and edge",
                collectionSize,
                depth = 1,
                filterCount = 2,
                get {
                    collection("users", "age" lt 80)
                    connection("practices", "hobbies", connectionCondition = "years_active" gt 10)
                }
            )
        )
    }
}