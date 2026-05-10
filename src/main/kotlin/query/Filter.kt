package ch.flavianz.query

import ch.flavianz.model.DataType

enum class FilterOperand {
    SMALLER_THAN,
    GREATER_THAN,
    EQUALS;

    fun isDataTypeApplicable(dataType: DataType): Boolean {
        return when (this) {
            SMALLER_THAN, GREATER_THAN -> dataType == DataType.INT
            EQUALS -> true
        }
    }

    fun isTypeApplicable(value: Any): Boolean {
        return when (this) {
            SMALLER_THAN, GREATER_THAN -> value is Int
            EQUALS -> true
        }
    }
}

data class Filter(val propertyName: String, val operand: FilterOperand, val comparisonValue: Any) {
    init {
        if(!operand.isTypeApplicable(comparisonValue)) {
            throw IllegalStateException("Operand $operand does not allow values of type ${comparisonValue.javaClass}")
        }
    }
}
