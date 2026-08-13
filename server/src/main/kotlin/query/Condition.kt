package query

sealed class Condition {
    sealed class Comparison : Condition() {
        abstract val field: String
        abstract val value: Any?

        data class Equals(override val field: String, override val value: Any?) : Comparison() {
            override fun toString(): String {
                return "'${field}' = $value"
            }
        }

        data class GreaterThan(override val field: String, override val value: Number) : Comparison() {
            override fun toString(): String {
                return "'$field' > $value"
            }
        }

        data class LessThan(override val field: String, override val value: Number) : Comparison() {
            override fun toString(): String {
                return "'$field' < $value"
            }
        }
    }

    sealed class Logic : Condition() {
        abstract val left: Condition
        abstract val right: Condition

        data class And(override val left: Condition, override val right: Condition) : Logic() {
            override fun toString(): String {
                return "$left and $right"
            }
        }

        data class Or(override val left: Condition, override val right: Condition) : Logic() {
            override fun toString(): String {
                return "$left or $right"
            }
        }
    }

    data class Not(val condition: Condition) : Condition() {
        override fun toString(): String {
            return "not $condition"
        }
    }

    data class In(val field: String, val list: Set<Any?>) : Condition() {
        override fun toString(): String {
            return "'$field' in [${list.joinToString { ", " }}]"
        }
    }
}
