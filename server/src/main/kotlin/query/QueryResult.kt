package query

import model.PolyData
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.math.max
import kotlin.time.Duration

class PolyQueryResult(
    val resultData: PolyResultData,
    val queryDuration: PolyQueryDuration,
    val executionEnvironment: PolyExecutionEnvironment
) {
    fun toJson() = buildJsonObject {
        put("duration", buildJsonObject {
            put("queryBuilding", queryDuration.queryBuildingDuration.toString())
            put("queryExecuting", queryDuration.queryExecutionDuration.toString())
            put("processing", queryDuration.processingDuration.toString())
        })
        put("env", buildJsonObject {
            put("driver", executionEnvironment.driver.toString())
            put("executedQueries", buildJsonArray {
                for (string in executionEnvironment.executedQueries) {
                    add(string)
                }
            })
        })
        put("data", resultData.toJson())
    }.toString()
}

data class PolyDriverQueryDuration(
    val queryBuildingDuration: Duration,
    val queryExecutionDuration: Duration,
)

data class PolyQueryDuration(
    val queryBuildingDuration: Duration,
    val queryExecutionDuration: Duration,
    val processingDuration: Duration
)

data class PolyExecutionEnvironment(
    val driver: DriverType,
    val executedQueries: List<String>
)

enum class DriverType {
    Postgres,
    Mongo,
    Neo4j;

    override fun toString(): String {
        return when (this) {
            Postgres -> "postgres"
            Mongo -> "mongo"
            Neo4j -> "neo4j"
        }
    }
}

sealed class PolyResultData {
    fun toJson(): JsonObject = when (this) {
        is Count -> buildJsonObject {
            put("type", "count")
            put("count", count)
        }

        is Documents -> buildJsonObject {
            put("type", "documents")
            put("data", buildJsonArray {
                for (row in polyData) {
                    add(buildJsonObject {
                        val segments = row.entries.groupBy { it.key.split(".").first() }
                        for (segment in segments) {
                            put(segment.key, buildJsonObject {
                                for ((key, value) in segment.value) {
                                    val subKey = key.split(".")[1]
                                    when (value) {
                                        is Int -> put(subKey, value)
                                        is Float, is Double -> put(subKey, value)
                                        is Boolean -> put(subKey, value)
                                        is String -> put(subKey, value)
                                        is UUID -> put(subKey, value.toString())
                                        null -> put(subKey, JsonNull)
                                    }
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    data class Documents(val polyData: List<PolyData>) : PolyResultData() {
        override fun toString(): String {
            if (polyData.isEmpty()) {
                return "\n----------------\n" + "| Empty Result |\n" + "----------------\n"
            }
            val string = StringBuilder("\n")
            val columns = polyData.flatMap { it.keys }.distinct()
            val maxWidths = columns.associateWith { column ->
                max(polyData.maxOfOrNull { it[column].toString().length } ?: 0,
                    column.length)
            }
            string.append("-".repeat(maxWidths.values.sum() + maxWidths.values.size * 3 + 1)).append("\n")
            for (column in columns) {
                string.append("| ${String.format("%-${maxWidths[column]}s", column)} ")
            }
            string.append("|\n")
            string.append("-".repeat(maxWidths.values.sum() + maxWidths.values.size * 3 + 1)).append("\n")
            for (result in polyData) {
                for (column in columns) {
                    string.append("| ${String.format("%-${maxWidths[column]}s", result[column])} ")
                }
                string.append("|\n")
            }
            string.append("-".repeat(maxWidths.values.sum() + maxWidths.values.size * 3 + 1)).append("\n")
            return string.toString()
        }
    }

    data class Count(val count: Int) : PolyResultData()
}

data class GetQueryResult(
    val data: List<PolyData>,
    val duration: PolyDriverQueryDuration,
    val executionEnvironment: PolyExecutionEnvironment
)