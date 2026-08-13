package model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.reflect.KClass

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

    fun toType(): KClass<*> {
        return when (this) {
            INT -> Int::class
            STRING -> String::class
            FLOAT -> Float::class
            UUID -> UUID::class
            BOOLEAN -> Boolean::class
            NULL -> throw IllegalArgumentException("cannot get type of null")
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