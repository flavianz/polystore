package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyTerminal
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
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(0, 20)),
                    "male" to PolyValue.of(false)
                )
            )
        }
        for (i in 0..<10) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(80, 100)),
                    "male" to PolyValue.of(true)
                )
            )
        }
        for (i in 0..<(collectionSize - 20)) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(20, 80)),
                    "male" to PolyValue.of(false)
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
            DriverManager.benchmarkTake(
                runId,
                queryShape = "collection collect all",
                collectionSize,
                depth = 1,
                filterCount = 0,
                PolyQuery(
                    QueryPath(listOf(QuerySegment.Collection("users"))),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
            )
        )
    }

    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "collection collect 1 filter",
                collectionSize,
                depth = 1,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
            )
        )
    }

    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "collection collect 2 filter",
                collectionSize,
                depth = 1,
                filterCount = 2,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Comparison.Equals("male", PolyValue.of(true))
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
            )
        )
    }

    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "collection collect 3 filter",
                collectionSize,
                depth = 1,
                filterCount = 3,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Logic.And(
                                        Condition.Comparison.GreaterThan("age", PolyValue.of(59)),
                                        Condition.Comparison.Equals("male", PolyValue.of(true))
                                    )
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
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
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                        "male" to PolyValue.of(faker.bool().bool())
                    )
                )
            )
        }
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                    "male" to PolyValue.of(faker.bool().bool())
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
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect all",
                collectionSize,
                depth = 2,
                filterCount = 0,
                PolyQuery(
                    QueryPath(listOf(QuerySegment.Collection("users"), QuerySegment.Collection("children"))),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
    }

    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect 1 filter",
                collectionSize,
                depth = 2,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection(
                                "children",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
    }

    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect 2 filter",
                collectionSize,
                depth = 2,
                filterCount = 2,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection(
                                "children",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Comparison.Equals("male", PolyValue.of(true))
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
    }

    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect 3 filter",
                collectionSize,
                depth = 2,
                filterCount = 3,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection(
                                "children",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Logic.Or(
                                        Condition.Comparison.GreaterThan("age", PolyValue.of(59)),
                                        Condition.Comparison.Equals("male", PolyValue.of(true))
                                    )
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
    }

    private fun bench5() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect 2 filter parent 1 filter",
                collectionSize,
                depth = 2,
                filterCount = 3,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                            ),
                            QuerySegment.Collection(
                                "children",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Comparison.GreaterThan("age", PolyValue.of(59))
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
    }

    private fun bench6() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "sub collection collect 2 filter parent 2 filter",
                collectionSize,
                depth = 2,
                filterCount = 4,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Comparison.GreaterThan("age", PolyValue.of(59))
                                )
                            ),
                            QuerySegment.Collection(
                                "children",
                                Condition.Logic.And(
                                    Condition.Comparison.LessThan("age", PolyValue.of(80)),
                                    Condition.Comparison.GreaterThan("age", PolyValue.of(59))
                                )
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
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
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                        "male" to PolyValue.of(faker.bool().bool())
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
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                        "male" to PolyValue.of(faker.bool().bool())
                    ), userIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }

        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                    "male" to PolyValue.of(faker.bool().bool())
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
            DriverManager.benchmarkTake(
                runId,
                queryShape = "deep sub collection collect all",
                collectionSize,
                depth = 3,
                filterCount = 0,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection("children"),
                            QuerySegment.Collection("grandchildren")
                        )
                    ),
                    PolyTerminal.Take(
                        listOf(
                            FieldRef.Wildcard("users"),
                            FieldRef.Wildcard("children"),
                            FieldRef.Wildcard("grandchildren")
                        )
                    )
                ),
                PolyTerminal.Take(
                    listOf(
                        FieldRef.Wildcard("users"),
                        FieldRef.Wildcard("children"),
                        FieldRef.Wildcard("grandchildren")
                    )
                )
            )
        )
    }

    // 3-hop, filter only on leaf level
    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "deep sub collection leaf filter",
                collectionSize,
                depth = 3,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection("children"),
                            QuerySegment.Collection(
                                "grandchildren",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                            )
                        )
                    ),
                    PolyTerminal.Take(
                        listOf(
                            FieldRef.Wildcard("users"),
                            FieldRef.Wildcard("children"),
                            FieldRef.Wildcard("grandchildren")
                        )
                    )
                ),
                PolyTerminal.Take(
                    listOf(
                        FieldRef.Wildcard("users"),
                        FieldRef.Wildcard("children"),
                        FieldRef.Wildcard("grandchildren")
                    )
                )
            )
        )
    }

    // 3-hop, filter at every level: worst case for cost compounding
    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "deep sub collection filter every level",
                collectionSize,
                depth = 3,
                filterCount = 3,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Comparison.LessThan("age", PolyValue.of(80))
                            ),
                            QuerySegment.Collection(
                                "children",
                                Condition.Comparison.Equals("male", PolyValue.of(true))
                            ),
                            QuerySegment.Collection(
                                "grandchildren",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(59))
                            )
                        )
                    ),
                    PolyTerminal.Take(
                        listOf(
                            FieldRef.Wildcard("users"),
                            FieldRef.Wildcard("children"),
                            FieldRef.Wildcard("grandchildren")
                        )
                    )
                ),
                PolyTerminal.Take(
                    listOf(
                        FieldRef.Wildcard("users"),
                        FieldRef.Wildcard("children"),
                        FieldRef.Wildcard("grandchildren")
                    )
                )
            )
        )
    }

    // 2-hop query (users -> children) run inside the 3-level schema, so you can compare
    // depth=2 vs depth=3 while collection_size and schema shape are otherwise identical
    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "deep schema shallow query 2 hop",
                collectionSize,
                depth = 2,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Collection(
                                "children",
                                Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                            )
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
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
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(0, 100)),
                        "male" to PolyValue.of(faker.bool().bool())
                    )
                )
            )
        }

        val hobbyIds = mutableListOf<UUID>()
        for (i in 0..<20) {
            hobbyIds.add(
                DatabaseManager.insertDocument(
                    "hobbies", mapOf(
                        "name" to PolyValue.of(faker.hobby().activity())
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
                    mapOf("years_active" to PolyValue.of(faker.number().numberBetween(0, 30)))
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
            DriverManager.benchmarkTake(
                runId,
                queryShape = "connection collect all",
                collectionSize,
                depth = 1,
                filterCount = 0,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Connection("practices", "hobbies"),
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
            )
        )
    }

    // filter on the connection's own property (years_active) - exercises edge-property filtering,
    // which is a distinct code path from filtering on node/document fields in most drivers
    private fun bench2() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "connection filter on edge property",
                collectionSize,
                depth = 1,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Connection(
                                "practices",
                                "hobbies",
                                connectionCondition = Condition.Comparison.GreaterThan("years_active", PolyValue.of(10))
                            ),
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
            )
        )
    }

    // filter on the far-side node (hobbies.name) reached through the connection
    private fun bench3() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "connection filter on far node",
                collectionSize,
                depth = 1,
                filterCount = 1,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection("users"),
                            QuerySegment.Connection(
                                "practices",
                                "hobbies",
                                collectionCondition = Condition.Comparison.Equals(
                                    "name",
                                    PolyValue.of(faker.hobby().activity())
                                )
                            ),
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
            )
        )
    }

    // filters on both the near node and the edge property simultaneously - combined cost
    private fun bench4() {
        durationMeasurements.addAll(
            DriverManager.benchmarkTake(
                runId,
                queryShape = "connection filter near node and edge",
                collectionSize,
                depth = 1,
                filterCount = 2,
                PolyQuery(
                    QueryPath(
                        listOf(
                            QuerySegment.Collection(
                                "users",
                                Condition.Comparison.LessThan("age", PolyValue.of(80))
                            ),
                            QuerySegment.Connection(
                                "practices",
                                "hobbies",
                                connectionCondition = Condition.Comparison.GreaterThan("years_active", PolyValue.of(10))
                            ),
                        )
                    ),
                    PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
                ),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("hobbies")))
            )
        )
    }
}