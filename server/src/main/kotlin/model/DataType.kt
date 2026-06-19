package ch.flavianz.model

import kotlinx.serialization.Serializable

@Serializable
enum class DataType {
    STRING,
    INT,
    FLOAT,
    UUID,
    BOOLEAN,
    NULL;


    fun toPostgresType(): String {
        return when (this) {
            STRING -> "TEXT"
            INT -> "INTEGER"
            FLOAT -> "FLOAT"
            UUID -> "UUID"
            BOOLEAN -> "BOOLEAN"
            NULL -> "NULL"
        }
    }
}