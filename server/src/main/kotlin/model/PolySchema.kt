package model

typealias PolySchema = Map<String, DataType>

fun PolySchema.toJson(): String {
    return "[${entries.joinToString(",") { "{\"name\": \"${it.key}\", \"type\": \"${it.value}\"}" }}]"
}