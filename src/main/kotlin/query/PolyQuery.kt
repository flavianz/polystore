package ch.flavianz.query

import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.QueryPath

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
    data class Documents(val polyData: List<PolyData>) : PolyResult()
    data class Count(val count: Int) : PolyResult()
}