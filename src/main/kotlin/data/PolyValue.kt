package ch.flavianz.data

import ch.flavianz.model.DataType
import java.util.UUID

sealed class PolyValue {
    abstract val type: DataType

    sealed class Number : PolyValue()

    data class IntValue(val value: Int) : Number() {
        override val type = DataType.INT
    }

    data class StringValue(val value: String) : PolyValue() {
        override val type = DataType.STRING
    }
    data class UUIDValue(val value: UUID) : PolyValue() {
        override val type = DataType.UUID
    }
    object NullValue : PolyValue() {
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
}