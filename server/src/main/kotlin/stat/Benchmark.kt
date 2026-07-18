package ch.flavianz.stat

import net.datafaker.Faker
import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.DataType
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyTerminal
import java.io.File
import java.util.Random
import java.util.UUID
import kotlin.collections.mutableListOf

object Benchmark {
    val runId = 0;
    val faker = Faker(Random(55L))
    val measurements = mutableListOf<DurationMeasurement>()
    /*fun startBenchmark() {
        val queryDurationStats = mutableListOf<QueryStats>()
        queryDurationStats.add(bench1())
        queryDurationStats.add(bench2())
        queryDurationStats.add(bench3())

        val csv = buildString {
            append("query;collection size;depth;filter count;driver;build average;build 90th percentile;build 95th percentile;build 99th percentile; build min; build max; build std deviation; build coefficient of variation;exec average;exec 90th percentile;exec 95th percentile;exec 99th percentile; exec min; build max; exec std deviation; exec coefficient of variation\n")
            for (stat in queryDurationStats) {
                for (driverPair in listOf(
                    Pair(stat.durationStats.postgres, "postgres"),
                    Pair(stat.durationStats.mongo, "mongo"),
                    Pair(stat.durationStats.neo4j, "neo4j")
                )) {
                    val driver = driverPair.first
                    append("${stat.name};${stat.collectionSize};${stat.depth};${stat.filterCount};${driverPair.second};${driver.buildingStats.avg};${driver.buildingStats.percentile90};${driver.buildingStats.percentile95};${driver.buildingStats.percentile99};${driver.buildingStats.min};${driver.buildingStats.max};${driver.buildingStats.stdDeviation};${driver.buildingStats.coefficientOfVariation};")
                    append("${driver.executionStats.avg};${driver.executionStats.percentile90};${driver.executionStats.percentile95};${driver.executionStats.percentile99};${driver.executionStats.min};${driver.executionStats.max};${driver.executionStats.stdDeviation};${driver.executionStats.coefficientOfVariation}\n")
                }
            }
        }

        File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\query bench\\${UUID.randomUUID()}.csv").writeText(
            csv
        )

        println()
        println("completed benchmark")
    }*/

    fun startBenchmark() {
        for (bench in listOf(BenchEnvironment1(runId))) {
            bench.init()
            measurements.addAll(BenchEnvironment1(runId).bench())
            bench.cleanUp()
        }
        println("completed bench, preparing file...")
        val csv = buildString {
            append("run_id;query_shape;driver;collection_size;depth;filter_count;phase;iteration;duration\n")
            for (measurement in measurements) {
                append(measurement.toCsvRow())
                append("\n")
            }
        }
        File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\query bench\\run-${runId}.csv").writeText(
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