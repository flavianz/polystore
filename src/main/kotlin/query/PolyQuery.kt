package ch.flavianz.query

import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue

// --- The full query ---
data class PolyQuery(
    val path: List<PathNode>,
    val terminal: PolyTerminal
)


sealed class Condition {
    data class Equals(val field: String, val value: PolyValue) : Condition()
    data class GreaterThan(val field: String, val value: PolyValue.Number) : Condition()
    data class LessThan(val field: String, val value: PolyValue.Number) : Condition()
    data class And(val left: Condition, val right: Condition) : Condition()
    data class Or(val left: Condition, val right: Condition) : Condition()
    data class Not(val condition: Condition) : Condition()
}


data class PathNode(
    val collection: String,
    val condition: Condition? = null
)


sealed class FieldRef {
    abstract val collection: String

    data class Wildcard(override val collection: String) : FieldRef()
    data class Named(override val collection: String, val field: String) : FieldRef()
}


sealed class PolyTerminal {
    data class Take(val fields: List<FieldRef>) : PolyTerminal()
    object Count : PolyTerminal()
}

sealed class PolyResult {
    data class Documents(val polyData: List<PolyData>) : PolyResult()
    data class Count(val count: Int) : PolyResult()
}