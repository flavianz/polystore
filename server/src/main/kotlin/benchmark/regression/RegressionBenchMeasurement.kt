package benchmark.regression

import benchmark.MeasurementPhase
import core.DatabaseManager
import query.Condition
import query.DriverType
import query.GetQuery
import query.QuerySegment
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

data class RegressionBenchMeasurement(
    val driver: DriverType,
    val collectionSize: Int,
    val queryProperties: QueryProperties,
    val phase: MeasurementPhase,
    val iteration: Int,
    val duration: Duration
) {
    override fun toString(): String {
        return "$driver;$collectionSize;$queryProperties;$phase;$iteration;${duration.inWholeMicroseconds}"
    }
}

data class QueryProperties(
    val singleCollectionSegmentCount: Int,
    val pairCollectionSegmentCount: Int,
    val connectionSegmentCount: Int,
    val multiQueryCount: Int,
    val rootFilterCounts: FilterCounts,
    val nestedFilterCounts: FilterCounts,
    val firstFilterDepth: Int,
    val onlyResultFraction: Double,
    val dynamicFilterFraction: Double,
    val dynamicResultFraction: Double,
) {
    override fun toString(): String {
        return "$singleCollectionSegmentCount;$pairCollectionSegmentCount;" +
                "$connectionSegmentCount;$multiQueryCount;$rootFilterCounts;$nestedFilterCounts;$firstFilterDepth;" +
                "$onlyResultFraction;$dynamicFilterFraction;$dynamicResultFraction"
    }
}

fun parseQueryProperties(
    query: GetQuery
): QueryProperties {
    val path = query.path
    var i = 0

    var singleCollectionSegmentCount = 0
    var pairCollectionSegmentCount = 0
    var connectionSegmentCount = 0

    var multiQueryCount = 0

    var firstFilterDepth = path.size

    val rootFilterCounts = FilterCounts(0, 0, 0, 0)
    val nestedFilterCounts = FilterCounts(0, 0, 0, 0)

    var onlyResultCount = 0
    var docResultCount = 0

    var dynamicFilterCount = 0
    var columnFilterCount = 0

    var dynamicResultCount = 0
    var columnResultCount = 0

    fun addFilterCount(
        condition: Condition?,
        filterCounts: FilterCounts,
        schemaKeys: Set<String>,
        isConnection: Boolean = false
    ) {
        when (condition) {
            null -> {}
            is Condition.In -> {
                if (condition.field == "_id" && !isConnection) {
                    filterCounts.idFilterCount++
                } else {
                    filterCounts.valueInListFilterCount++
                }
                if ((condition.field == "_id" && !isConnection) || schemaKeys.contains(condition.field)) {
                    columnFilterCount++
                } else {
                    dynamicFilterCount++
                }
            }

            is Condition.Comparison.Equals -> {
                if (condition.field == "_id" && !isConnection) {
                    filterCounts.idFilterCount++
                } else {
                    filterCounts.equalityFilterCount++
                }
                if ((condition.field == "_id" && !isConnection) || schemaKeys.contains(condition.field)) {
                    columnFilterCount++
                } else {
                    dynamicFilterCount++
                }
            }

            is Condition.Comparison.GreaterThan, is Condition.Comparison.LessThan -> {
                filterCounts.numberRangeFilterCount++
                if ((condition.field == "_id" && !isConnection) || schemaKeys.contains(condition.field)) {
                    columnFilterCount++
                } else {
                    dynamicFilterCount++
                }
            }

            is Condition.Logic -> {
                addFilterCount(condition.left, filterCounts, schemaKeys)
                addFilterCount(condition.right, filterCounts, schemaKeys)
            }

            is Condition.Not -> {
                addFilterCount(condition.condition, filterCounts, schemaKeys)
            }
        }
    }

    while (i < path.size) {
        val segment = path[i]
        when (segment) {
            is QuerySegment.Collection -> {
                val nextSegment = path.getOrNull(i + 1)
                if (nextSegment is QuerySegment.Collection) {
                    pairCollectionSegmentCount++
                    i += 2
                } else {
                    singleCollectionSegmentCount++
                    i++
                }
            }

            is QuerySegment.Connection -> {
                connectionSegmentCount++
                if (i == 1 && singleCollectionSegmentCount == 1) {
                    singleCollectionSegmentCount--
                }
                i++
            }
        }
    }
    i = 0
    while (i < path.size) {
        val segment = path[i]
        if (segment is QuerySegment.Collection) {
            val collectionSchema = DatabaseManager.getCollectionModel(segment.name).schema
            addFilterCount(segment.condition, rootFilterCounts, collectionSchema.keys)
            when (val nextSegment = path.getOrNull(i + 1)) {
                is QuerySegment.Collection -> {
                    val collectionSchema = DatabaseManager.getCollectionModel(nextSegment.name).schema
                    addFilterCount(nextSegment.condition, nestedFilterCounts, collectionSchema.keys)
                    i += 2
                }

                is QuerySegment.Connection -> {
                    val collectionSchema = DatabaseManager.getCollectionModel(nextSegment.collectionName).schema
                    val connectionSchema =
                        DatabaseManager.getConnectionModel(nextSegment.connectionName).connectionDataSchema
                    addFilterCount(nextSegment.connectionCondition, nestedFilterCounts, connectionSchema.keys)
                    addFilterCount(nextSegment.collectionCondition, nestedFilterCounts, collectionSchema.keys)
                    if (multiQueryCount != 0) {
                        addFilterCount(Condition.In("_id", emptySet<UUID>()), nestedFilterCounts, emptySet())
                    }
                    i += 2
                }

                else -> {
                    i += 1
                }
            }
        } else if (segment is QuerySegment.Connection) {
            val connectionSchema = DatabaseManager.getConnectionModel(segment.connectionName).connectionDataSchema
            addFilterCount(segment.connectionCondition, nestedFilterCounts, connectionSchema.keys)
            addFilterCount(segment.collectionCondition, nestedFilterCounts, connectionSchema.keys)
            addFilterCount(Condition.In("_id", emptySet<UUID>()), nestedFilterCounts, connectionSchema.keys)
            i++
        }
        multiQueryCount++
    }
    for ((depth, segment) in path.withIndex()) {
        when (segment) {
            is QuerySegment.Collection -> {
                if (segment.condition != null) {
                    firstFilterDepth = min(depth, firstFilterDepth)
                }
                val collectionSchema = DatabaseManager.getCollectionModel(segment.name).schema
                if (segment.only == null) {
                    dynamicResultCount++
                    docResultCount++
                } else {
                    onlyResultCount++
                    if (segment.only.firstOrNull { !(it == "_id" || collectionSchema.contains(it)) } == null) {
                        columnResultCount++
                    } else {
                        dynamicResultCount++
                    }
                }
            }

            is QuerySegment.Connection -> {
                if (segment.connectionCondition != null || segment.collectionCondition != null) {
                    firstFilterDepth = min(depth, firstFilterDepth)
                }
                val collectionSchema = DatabaseManager.getCollectionModel(segment.collectionName).schema
                if (segment.collectionOnly == null) {
                    dynamicResultCount++
                    docResultCount++
                } else {
                    onlyResultCount++
                    if (segment.collectionOnly.firstOrNull { !(it == "_id" || collectionSchema.contains(it)) } == null) {
                        columnResultCount++
                    } else {
                        dynamicResultCount++
                    }
                }
                val connectionSchema = DatabaseManager.getConnectionModel(segment.connectionName).connectionDataSchema
                if (segment.connectionOnly == null) {
                    dynamicResultCount++
                    docResultCount++
                } else {
                    onlyResultCount++
                    if (segment.connectionOnly.firstOrNull { !(connectionSchema.contains(it)) } == null) {
                        columnResultCount++
                    } else {
                        dynamicResultCount++
                    }
                }
            }
        }

    }
    return QueryProperties(
        singleCollectionSegmentCount,
        pairCollectionSegmentCount, connectionSegmentCount,
        multiQueryCount,
        rootFilterCounts,
        nestedFilterCounts,
        firstFilterDepth,
        onlyResultCount.toDouble() / max(onlyResultCount + docResultCount, 1),
        dynamicFilterCount.toDouble() / max(dynamicFilterCount + columnFilterCount, 1),
        dynamicResultCount.toDouble() / max(dynamicResultCount + columnResultCount, 1),
    )
}

data class FilterCounts(
    var idFilterCount: Int,
    var valueInListFilterCount: Int,
    var equalityFilterCount: Int,
    var numberRangeFilterCount: Int,
) {
    override fun toString(): String {
        return "$idFilterCount;$valueInListFilterCount;$equalityFilterCount;$numberRangeFilterCount"
    }
}