package ch.flavianz.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DataType {
    STRING,
    INT,
    FLOAT,
    UUID,
    BOOLEAN,
    NULL;

    fun matchesType(value: Any?): Boolean {
        return when (value) {
            is Int -> this == INT
            is Float -> this == FLOAT
            is Boolean -> this == BOOLEAN
            is String -> this == STRING
            is UUID -> this == UUID
            null -> this == NULL
            else -> false
        }
    }


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