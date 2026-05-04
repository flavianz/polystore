package ch.flavianz.core.model

enum class DataType {
    String,
    Int;


    fun toPostgresType(): String {
        return when (this) {
            String -> "TEXT"
            Int -> "INTEGER"
        }
    }
}