package ch.flavianz.query

import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.QueryPath
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max

// --- The full query ---
data class PolyQuery(
    val path: QueryPath,
    val terminal: PolyTerminal
)


sealed class Condition {
    sealed class Comparison : Condition() {
        abstract val field: String
        abstract val value: PolyValue

        data class Equals(override val field: String, override val value: PolyValue) : Comparison()
        data class GreaterThan(override val field: String, override val value: PolyValue.Number) : Comparison()
        data class LessThan(override val field: String, override val value: PolyValue.Number) : Comparison()
    }

    sealed class Logic : Condition() {
        abstract val left: Condition
        abstract val right: Condition

        data class And(override val left: Condition, override val right: Condition) : Logic()
        data class Or(override val left: Condition, override val right: Condition) : Logic()
    }

    data class Not(val condition: Condition) : Condition()

    data class In(val field: String, val list: List<PolyValue>) : Condition()
}

sealed class FieldRef {
    abstract val segment: String

    data class Wildcard(override val segment: String) : FieldRef()
    data class Named(override val segment: String, val field: String) : FieldRef()
}


sealed class PolyTerminal {
    data class Take(val fields: List<FieldRef>) : PolyTerminal()
    object Count : PolyTerminal()
}

sealed class PolyResult {
    fun toJson(): String = when (this) {
        is Count -> buildJsonObject {
            put("type", "count")
            put("count", count)
        }.toString()

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
                                        is PolyValue.IntValue -> put(subKey, value.value)
                                        is PolyValue.StringValue -> put(subKey, value.value)
                                        is PolyValue.UUIDValue -> put(subKey, value.value.toString())
                                        is PolyValue.NullValue -> put(subKey, JsonNull)
                                    }
                                }
                            })
                        }
                    })
                }
            })
        }.toString()
    }

    data class Documents(val polyData: List<PolyData>) : PolyResult() {
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

    data class Count(val count: Int) : PolyResult()
}