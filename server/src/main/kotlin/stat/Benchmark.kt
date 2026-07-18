package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import net.datafaker.Faker
import java.io.File
import java.util.Random
import kotlin.collections.mutableListOf

object Benchmark {
    val seed = Random(55L)
    const val RUN_ID = 1
    val faker = Faker(seed)
    val measurements = mutableListOf<DurationMeasurement>()

    fun startBenchmark() {
        for (connection in DatabaseManager.listConnections()) {
            DatabaseManager.dropConnection(connection.name)
        }
        for (collection in DatabaseManager.listCollections()) {
            if (DatabaseManager.existsCollection(collection.name)) {
                DatabaseManager.dropCollection(collection.name, true)
            }

        }
        for (env in listOf(
            BenchEnvironmentSimpleCollection(RUN_ID, 100),
            BenchEnvironmentSimpleCollection(RUN_ID, 5000),
            BenchEnvironmentSimpleCollection(RUN_ID, 100_000),
            BenchEnvironmentSubCollection(RUN_ID, 100),
            BenchEnvironmentSubCollection(RUN_ID, 5000),
            BenchEnvironmentSubCollection(RUN_ID, 100_000),
            BenchEnvironmentDeepSubCollection(RUN_ID, 100),
            BenchEnvironmentDeepSubCollection(RUN_ID, 5000),
            BenchEnvironmentDeepSubCollection(RUN_ID, 100_000),
            BenchEnvironmentConnection(RUN_ID, 100),
            BenchEnvironmentConnection(RUN_ID, 5000),
            BenchEnvironmentConnection(RUN_ID, 100_000),
        )) {
            env.init()
            measurements.addAll(env.bench())
            env.cleanUp()
        }
        println("completed bench, preparing file...")
        val csv = buildString {
            append("run_id;query_shape;driver;collection_size;depth;filter_count;phase;iteration;duration\n")
            for (measurement in measurements) {
                append(measurement.toCsvRow())
                append("\n")
            }
        }
        File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\bench\\bench-data-raw.csv").appendText(
            csv
        )
    }

    /*// all documents from a small collection
    private fun bench1(): List<DurationMeasurement> {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT
            )
        )
        for (i in 0..<20) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(80, 100))
                )
            )
        }
        val result = DriverManager.benchmarkTake(
            runId,

            "collection take",
            PolyQuery(
                QueryPath(listOf(QuerySegment.Collection("users"))),
                PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
            )
        )
        DatabaseManager.dropCollection("users")
        return result
    }

    // get 20 documents that match one filter in a collection of 1000 docs
    private fun bench2(): DriverSpecificData<QueryDurationStats> {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT
            )
        )
        for (i in 0..<490) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(0, 80))
                )
            )
        }
        for (i in 0..<20) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(80, 100))
                )
            )
        }

        for (i in 0..<490) {
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to PolyValue.of(faker.name().firstName()),
                    "age" to PolyValue.of(faker.number().numberBetween(0, 80))
                )
            )
        }
        val result = analyzeQuery(
            "collection take one filter", PolyQuery(
                QueryPath(
                    listOf(
                        QuerySegment.Collection(
                            "users",
                            Condition.Comparison.GreaterThan("age", PolyValue.of(79))
                        )
                    )
                ), PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))
            )
        )
        DatabaseManager.dropCollection("users")
        return result
    }

    // get all documents and subdocuments
    private fun bench3(): DriverSpecificData<QueryDurationStats> {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT
            )
        )
        DatabaseManager.createCollection(
            "children", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT
            ), "users"
        )
        val ids = mutableListOf<UUID>()
        for (i in 0..<10) {
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(0, 80))
                    )
                )
            )
        }
        for (id in ids) {
            for (i in 0..<3) {
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to PolyValue.of(faker.name().firstName()),
                        "age" to PolyValue.of(faker.number().numberBetween(80, 100))
                    ), id
                )
            }
        }
        val result = analyzeQuery(
            "take collection and subcollection", PolyQuery(
                QueryPath(
                    listOf(
                        QuerySegment.Collection(
                            "users",
                        ), QuerySegment.Collection("children")
                    )
                ), PolyTerminal.Take(listOf(FieldRef.Wildcard("users"), FieldRef.Wildcard("children")))
            )
        )
        DatabaseManager.dropCollection("users", true)
        return result
    }*/
}