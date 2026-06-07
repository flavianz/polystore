package ch.flavianz.data

import ch.flavianz.model.DataType
import java.util.UUID

sealed class PolyValue {
    abstract val type: DataType
    abstract val value: Any?

    sealed class Number : PolyValue()

    data class IntValue(override val value: Int) : Number() {
        override val type = DataType.INT
        override fun toString(): String {
            return value.toString()
        }
    }

    data class StringValue(override val value: String) : PolyValue() {
        override val type = DataType.STRING
        override fun toString(): String {
            return value
        }
    }

    data class UUIDValue(override val value: UUID) : PolyValue() {
        override val type = DataType.UUID
        override fun toString(): String {
            return value.toString()
        }
    }

    object NullValue : PolyValue() {
        override val value = null
        override val type = DataType.NULL
    }

    companion object {
        fun of(value: String) = StringValue(value)
        fun of(value: Int) = IntValue(value)
        fun of(value: UUID) = UUIDValue(value)
    }

    fun isType(dataType: DataType): Boolean {
        return this.type == dataType
    }

    fun getIntValue(): Int {
        return value as Int
    }

    override fun toString(): String {
        return value.toString()
    }
}