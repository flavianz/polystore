package driver

import connection.MongoConnection
import connection.Neo4jConnection
import model.DatabaseSchema
import model.PolyData
import query.Condition
import query.DriverType
import query.GetQuery
import query.PolyExecutionEnvironment
import query.GetQueryResult
import query.QuerySegment
import benchmark.BenchmarkQuery
import benchmark.DurationMeasurement
import benchmark.MeasurementPhase
import java.sql.Connection
import kotlin.collections.mutableListOf

object DriverManager {
    var postgresDriver: PostgresDriver? = null
    var mongoDriver: MongoDriver? = null
    var neo4jDriver: Neo4jDriver? = null

    fun execute(a: DatabaseDriver.() -> Unit) {
        postgresDriver?.a()
        mongoDriver?.a()
        neo4jDriver?.a()
    }

    fun initPostgres(jdbcConnection: Connection): DriverManager {
        val driver = PostgresDriver(jdbcConnection)
        driver.init()
        this.postgresDriver = driver
        return this
    }

    fun detachPostgres() {
        postgresDriver = null
    }

    fun initMongo(mongoConnection: MongoConnection): DriverManager {
        val driver = MongoDriver(mongoConnection.mongoDatabase)
        driver.init()
        this.mongoDriver = driver
        return this
    }

    fun detachMongo() {
        mongoDriver = null
    }

    fun initNeo4j(neo4jConnection: Neo4jConnection): DriverManager {
        val driver = Neo4jDriver(neo4jConnection)
        driver.init()
        this.neo4jDriver = driver
        return this
    }

    fun detachNeo4j() {
        neo4jDriver = null
    }

    fun getActiveDriver(): DatabaseDriver {
        return mongoDriver ?: postgresDriver ?: neo4jDriver ?: throw IllegalStateException("no driver connected")
    }

    fun getActiveDrivers(): List<DatabaseDriver> {
        return buildList {
            if (mongoDriver != null) {
                add(mongoDriver!!)
            }
            if (postgresDriver != null) {
                add(postgresDriver!!)
            }
            if (neo4jDriver != null) {
                add(neo4jDriver!!)
            }
        }
    }

    fun benchmarkGet(
        iterations: Int,
        runId: Int,
        collectionSize: Int,
        benchmarkQuery: BenchmarkQuery
    ): List<DurationMeasurement> {
        val (queryShape, depth, filterCount, filterType, query, benchResultType, _, dynamicData) = benchmarkQuery
        val measurements = mutableListOf<DurationMeasurement>()
        val results = mutableListOf<Set<PolyData>>()

        for (i in 0..<iterations) {
            if (iterations > 100 && i % 100 == 0) println("$i von $iterations queries complete")
            for (driver in listOf(
                Pair(postgresDriver, DriverType.Postgres),
                Pair(mongoDriver, DriverType.Mongo),
                Pair(neo4jDriver, DriverType.Neo4j)
            )) {
                val result = driver.first!!.get(query)
                if (i == 0) {
                    results.add(result.data.toSet())
                }
                if (i == 1) {
                    check(results.distinct().size == 1) { "not all drivers returned the same result for query '${queryShape}:\npostgres:(size ${results[0].size})${results[0]}\nmongo:(size ${results[1].size})${results[1]}\nneo4j:(size ${results[2].size})${results[2]}'" }
                    println("driver results equal")
                }
                measurements.add(
                    DurationMeasurement(
                        runId,
                        queryShape,
                        driver.second,
                        collectionSize,
                        depth,
                        filterCount,
                        filterType,
                        benchResultType,
                        dynamicData,
                        MeasurementPhase.Build,
                        i,
                        result.duration.queryBuildingDuration
                    )
                )
                measurements.add(
                    DurationMeasurement(
                        runId,
                        queryShape,
                        driver.second,
                        collectionSize,
                        depth,
                        filterCount,
                        filterType,
                        benchResultType,
                        dynamicData,
                        MeasurementPhase.Exec,
                        i,
                        result.duration.queryExecutionDuration
                    )
                )
                measurements.add(
                    DurationMeasurement(
                        runId,
                        queryShape,
                        driver.second,
                        collectionSize,
                        depth,
                        filterCount, filterType, benchResultType, dynamicData,
                        MeasurementPhase.Total,
                        i,
                        result.duration.queryBuildingDuration.plus(result.duration.queryExecutionDuration)
                    )
                )
            }
        }

        if (results.first().size > 2) {
            println("query '$queryShape' results in size '${results.first().size}' ")
        } else {
            println("query '$queryShape' results in '${results.first()}' ")
        }

        return measurements
    }

    fun get(query: GetQuery): GetQueryResult {
        val activeDriver = chooseDriverSimple(query)
        val result = activeDriver.get(query)
        return GetQueryResult(
            result.data, result.duration, PolyExecutionEnvironment(
                when (activeDriver) {
                    is PostgresDriver -> DriverType.Postgres
                    is MongoDriver -> DriverType.Mongo
                    is Neo4jDriver -> DriverType.Neo4j
                    else -> throw IllegalStateException("unexpected driver detected")
                }, result.executedQueries
            )
        )
    }

    fun chooseDriverSimple(query: GetQuery): DatabaseDriver {
        val isPostgresActive = postgresDriver != null
        val isMongoActive = mongoDriver != null
        val isNeo4jActive = neo4jDriver != null

        if (!isPostgresActive && !isMongoActive && !isNeo4jActive) {
            throw IllegalStateException("no driver active")
        }

        if (query.path.size == 1 && isPostgresActive) {
            return postgresDriver!!
        }

        assert(query.path[0] is QuerySegment.Collection)

        val conditions = buildList {
            for (segment in query.path) {
                when (segment) {
                    is QuerySegment.Connection -> {
                        segment.collectionCondition?.let { add(it) }
                        segment.connectionCondition?.let { add(it) }
                    }

                    is QuerySegment.Collection -> segment.condition?.let { add(it) }
                }
            }
        }
        val queryContainsRangeQuery =
            conditions.firstOrNull { it is Condition.Comparison && it !is Condition.Comparison.Equals } != null
        if ((queryContainsRangeQuery) && isPostgresActive) {
            return postgresDriver!!
        }

        val basicConditions = extractBasicConditions((query.path[0] as QuerySegment.Collection).condition)
        val conditionFields = basicConditions.map {
            when (it) {
                is Condition.In -> it.field
                is Condition.Comparison -> it.field
                else -> throw AssertionError()
            }
        }

        if (query.path[1] is QuerySegment.Collection) {
            if (conditions.isEmpty() && isPostgresActive) {
                return postgresDriver!!
            }
            if (conditionFields.firstOrNull { it != "_id" } == null && isMongoActive) {
                // only filters on id
                return mongoDriver!!
            }
            if (isPostgresActive) {
                return postgresDriver!!
            }
            if (isMongoActive) {
                return mongoDriver!!
            }
            return neo4jDriver!!
        }

        if (query.path[0] is QuerySegment.Connection) {
            if (conditions.isEmpty() && isPostgresActive) {
                return postgresDriver!!
            }
            if (conditionFields.firstOrNull { it != "_id" } == null) {
                // only filters on id
                if (isMongoActive) {
                    return mongoDriver!!
                }
                if (isNeo4jActive) {
                    return neo4jDriver!!
                }
            }
            if (isPostgresActive) {
                return postgresDriver!!
            }
            if (isMongoActive) {
                return mongoDriver!!
            }
            return neo4jDriver!!
        }
        if (query.path.size <= 5 && conditions.isEmpty() && isPostgresActive) {
            return postgresDriver!!
        }
        if (conditionFields.firstOrNull { it != "_id" } == null) {
            // only filters on id
            if (isNeo4jActive) {
                return neo4jDriver!!
            }
            if (isMongoActive) {
                return mongoDriver!!
            }
        }
        if (query.path.size >= 5) {
            if (isNeo4jActive) {
                return neo4jDriver!!
            }
            if (isPostgresActive) {
                return postgresDriver!!
            }
        }
        if (isPostgresActive) {
            return postgresDriver!!
        }
        if (isNeo4jActive) {
            return neo4jDriver!!
        }
        return mongoDriver!!
    }

    private fun extractBasicConditions(condition: Condition?): List<Condition> {
        return when (condition) {
            null -> emptyList()
            is Condition.Comparison, is Condition.In -> listOf(condition)
            is Condition.Not -> extractBasicConditions(condition)
            is Condition.Logic -> extractBasicConditions(condition.left) + extractBasicConditions(condition.right)
        }
    }

    /*fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResultData.Count {
        return getActiveDriver().count(query.path, terminal)
    }*/

    fun parseDatabaseSchema(): DatabaseSchema {
        val schemas = mutableListOf<DatabaseSchema>()
        mongoDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        postgresDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        neo4jDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        if (schemas.isEmpty()) {
            throw IllegalStateException("no source connected to parse schema from")
        }

        if (schemas.distinct().size > 1) {
            throw IllegalStateException("not all connected sources have the same schema: $schemas")
        }
        return schemas.first()
    }
}