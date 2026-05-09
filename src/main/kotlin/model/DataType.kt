package ch.flavianz.model

enum class DataType {
    STRING,
    INT;


    fun toPostgresType(): String {
        return when (this) {
            STRING -> "TEXT"
            INT -> "INTEGER"
        }
    }

    fun matchesType(value: Any?): Boolean {
        return when (this) {
            STRING -> value is String
            INT -> value is Int
        }
    }
}