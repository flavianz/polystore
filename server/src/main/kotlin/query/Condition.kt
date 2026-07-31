package ch.flavianz.query

sealed class Condition {
    sealed class Comparison : Condition() {
        abstract val field: String
        abstract val value: Any?

        data class Equals(override val field: String, override val value: Any?) : Comparison()
        data class GreaterThan(override val field: String, override val value: Number) : Comparison()
        data class LessThan(override val field: String, override val value: Number) : Comparison()
    }

    sealed class Logic : Condition() {
        abstract val left: Condition
        abstract val right: Condition

        data class And(override val left: Condition, override val right: Condition) : Logic()
        data class Or(override val left: Condition, override val right: Condition) : Logic()
    }

    data class Not(val condition: Condition) : Condition()

    data class In(val field: String, val list: Set<Any?>) : Condition()
}
