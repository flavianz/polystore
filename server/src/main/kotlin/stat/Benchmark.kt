package ch.flavianz.stat

import net.datafaker.Faker
import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyTerminal
import java.io.File
import java.util.UUID
import kotlin.collections.mutableListOf

object Benchmark {
    private val faker = Faker()
    fun startBenchmark() {
        val queryStats = mutableListOf<DriverSpecificData<QueryStats>>()
        queryStats.add(bench1())
        queryStats.add(bench2())

        val csv = buildString {
            append("query;driver;build average;build 90th percentile;build 95th percentile;build 99th percentile; build min; build max; build std deviation; build coefficient of variation;exec average;exec 90th percentile;exec 95th percentile;exec 99th percentile; exec min; build max; exec std deviation; exec coefficient of variation\n")
            for(stat in queryStats) {
                for(driverPair in listOf(Pair(stat.postgres, "postgres"), Pair(stat.mongo, "mongo"), Pair(stat.neo4j, "neo4j"))) {
                    val driver = driverPair.first
                    append("${driver.name};${driverPair.second};${driver.buildingStats.avg};${driver.buildingStats.percentile90};${driver.buildingStats.percentile95};${driver.buildingStats.percentile99};${driver.buildingStats.min};${driver.buildingStats.max};${driver.buildingStats.stdDeviation};${driver.buildingStats.coefficientOfVariation};")
                    append("${driver.executionStats.avg};${driver.executionStats.percentile90};${driver.executionStats.percentile95};${driver.executionStats.percentile99};${driver.executionStats.min};${driver.executionStats.max};${driver.executionStats.stdDeviation};${driver.executionStats.coefficientOfVariation}\n")
                }
            }
        }

        File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\query bench\\${UUID.randomUUID()}.csv").writeText(csv)

        println()
        println("completed benchmark")
    }

    // all documents from a small collection
    private fun bench1(): DriverSpecificData<QueryStats> {
        DatabaseManager.createCollection("users", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT
        ))/*
        for(i in 0..<980) {
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(0, 80))
            ))
        }*/
        for(i in 0..<20) {
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(80, 100))
            ))
        }
        /*for(i in 0..<10000) {
            if(i % 1000 == 0) println(i)
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(0, 80))
            ))
        }*/
        val result = analyzeQuery("collection take", PolyQuery(QueryPath(listOf(QuerySegment.Collection("users",
            /*Condition.Comparison.GreaterThan("age", PolyValue.of(79))*/))), PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))))
        DatabaseManager.dropCollection("users")
        return result
    }

    // get 20 documents that match one filter in a collection of 1000 docs
    private fun bench2(): DriverSpecificData<QueryStats> {
        DatabaseManager.createCollection("users", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT
        ))
        for(i in 0..<490) {
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(0, 80))
            ))
        }
        for(i in 0..<20) {
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(80, 100))
            ))
        }

        for(i in 0..<490) {
            DatabaseManager.insertDocument("users", mapOf(
                "name" to PolyValue.of(faker.name().firstName()),
                "age" to PolyValue.of(faker.number().numberBetween(0, 80))
            ))
        }
        val result = analyzeQuery("collection take one filter", PolyQuery(QueryPath(listOf(QuerySegment.Collection("users",
            Condition.Comparison.GreaterThan("age", PolyValue.of(79))))), PolyTerminal.Take(listOf(FieldRef.Wildcard("users")))))
        DatabaseManager.dropCollection("users")
        return result
    }
}