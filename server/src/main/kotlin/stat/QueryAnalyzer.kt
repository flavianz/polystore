package ch.flavianz.stat

import ch.flavianz.driver.DriverManager
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyTerminal
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration

fun analyzeQuery(name: String, query: PolyQuery): DriverSpecificData<QueryStats> {
    val data = DriverManager.benchmarkTake(query, query.terminal as PolyTerminal.Take)
    val ignoredPostgresData = data.postgres.subList(0, 100)
    val ignoredMongoData = data.mongo.subList(0, 100)
    val ignoredNeo4jData = data.neo4j.subList(0, 100)
    val postgresData = data.postgres.subList(100, data.postgres.size)
    val mongoData = data.mongo.subList(100, data.mongo.size)
    val neo4jData = data.neo4j.subList(100, data.neo4j.size)

    return DriverSpecificData(
        QueryStats(
            name,
            calculateQueryStats(postgresData.map { it.queryBuildingDuration }),
            calculateQueryStats(postgresData.map { it.queryExecutionDuration }),
            calculateQueryStats(ignoredPostgresData.map { it.queryBuildingDuration }),
            calculateQueryStats(ignoredPostgresData.map { it.queryExecutionDuration })
        ), QueryStats(
            name,
            calculateQueryStats(mongoData.map { it.queryBuildingDuration }),
            calculateQueryStats(mongoData.map { it.queryExecutionDuration }),
            calculateQueryStats(ignoredMongoData.map { it.queryBuildingDuration }),
            calculateQueryStats(ignoredMongoData.map { it.queryExecutionDuration })
        ), QueryStats(
            name,
            calculateQueryStats(neo4jData.map { it.queryBuildingDuration }),
            calculateQueryStats(neo4jData.map { it.queryExecutionDuration }),
            calculateQueryStats(ignoredNeo4jData.map { it.queryBuildingDuration }),
            calculateQueryStats(ignoredNeo4jData.map { it.queryExecutionDuration })
        )
    )
}

fun calculateQueryStats(data: List<Duration>): QueryStatData {
    val microSecondData = data.map { it.inWholeMicroseconds }
    val avg = microSecondData.average()
    val percentile90 = microSecondData.percentile(90.0)
    val percentile95 = microSecondData.percentile(95.0)
    val percentile99 = microSecondData.percentile(99.0)
    val min = microSecondData.min()
    val max = microSecondData.max()
    val stdDeviation = microSecondData.standardDeviation()
    val coefficientOfVariation = microSecondData.coefficientOfVariation()

    return QueryStatData(microSecondData, avg, percentile90, percentile95, percentile99, min, max, stdDeviation, coefficientOfVariation)
}

fun List<Long>.standardDeviation(): Double {
    require(isNotEmpty()) { "List must not be empty." }

    val mean = average()

    val variance = sumOf { value ->
        val diff = value - mean
        diff * diff
    } / size

    return sqrt(variance)
}

fun List<Long>.coefficientOfVariation(): Double {
    require(isNotEmpty()) { "List must not be empty." }

    val mean = average()
    require(mean != 0.0) { "Coefficient of variation is undefined for a mean of zero." }

    return standardDeviation() / mean
}

fun List<Long>.percentile(percentile: Double): Double {
    require(isNotEmpty()) { "List must not be empty." }
    require(percentile in 0.0..100.0) { "Percentile must be between 0 and 100." }

    val sorted = sorted()

    if (size == 1) return sorted[0].toDouble()

    val index = percentile / 100.0 * (size - 1)

    val lower = floor(index).toInt()
    val upper = ceil(index).toInt()

    if (lower == upper) {
        return sorted[lower].toDouble()
    }

    val fraction = index - lower
    return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
}