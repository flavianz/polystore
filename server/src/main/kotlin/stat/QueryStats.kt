package ch.flavianz.stat

data class QueryStats (
    val name: String,
    val buildingStats: QueryStatData,
    val executionStats: QueryStatData,
    val ignoredBuildingStats: QueryStatData,
    val ignoredExecutionStats: QueryStatData
)

data class QueryStatData(
    val measurements: List<Long>,
    val avg: Double,
    val percentile90: Double,
    val percentile95: Double,
    val percentile99: Double,
    val min: Long,
    val max: Long,
    val stdDeviation: Double,
    val coefficientOfVariation: Double
)

data class DriverSpecificData<T>(
    val postgres: T,
    val mongo: T,
    val neo4j: T,
)