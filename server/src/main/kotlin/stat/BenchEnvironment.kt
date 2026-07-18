package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.DataType
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyTerminal

interface BenchEnvironment {
    val runId: Int
    val collectionSize: Int
    fun init()
    fun cleanUp()
    fun bench(): List<DurationMeasurement>
}

class BenchEnvironment1(override val runId: Int) : BenchEnvironment {
    override val collectionSize = 100
    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()
    override fun init() {
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
                    "age" to PolyValue.of(faker.number().numberBetween(0, 100))
                )
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users")
    }

    override fun bench(): List<DurationMeasurement> {
        bench1()
        return durationMeasurements
    }

    private fun bench1() {
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
}