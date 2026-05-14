package ch.flavianz.model

enum class DataType {
    STRING,
    INT,
    UUID,
    NULL;


    fun toPostgresType(): String {
        return when (this) {
            STRING -> "TEXT"
            INT -> "INTEGER"
            UUID -> "UUID"
            NULL -> "NULL"
        }
    }
}