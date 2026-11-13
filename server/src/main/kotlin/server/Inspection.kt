package server

import core.DatabaseManager
import driver.DriverManager
import driver.MongoDriver
import driver.Neo4jDriver
import driver.PostgresDriver
import model.CollectionModel
import query.DriverType
import query.GetQuery
import query.QuerySegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.postgresql.util.PGobject
import java.util.UUID

fun parseDriverType(name: String): DriverType {
    return when (name.lowercase()) {
        "postgres" -> DriverType.Postgres
        "mongo" -> DriverType.Mongo
        "neo4j" -> DriverType.Neo4j
        else -> throw IllegalArgumentException("unknown driver '$name', expected one of: postgres, mongo, neo4j")
    }
}

fun parseQuerySegments(path: List<RequestQuerySegment>): List<QuerySegment> {
    val querySegments = mutableListOf<QuerySegment>()

    var i = 0
    while (i < path.size) {
        val requestSegment = path[i]
        when (requestSegment.type) {
            "collection" -> querySegments.add(
                QuerySegment.Collection(
                    requestSegment.name,
                    ConditionParser(requestSegment.condition).parse(),
                    only = requestSegment.only
                )
            )

            "connection" -> {
                require(i + 1 < path.size)
                val nextSegment = path[i + 1]
                require(nextSegment.type == "collection")
                querySegments.add(
                    QuerySegment.Connection(
                        requestSegment.name,
                        nextSegment.name,
                        ConditionParser(requestSegment.condition).parse(),
                        ConditionParser(nextSegment.condition).parse(),
                        requestSegment.only,
                        nextSegment.only
                    )
                )
                i++
            }

            else -> throw IllegalArgumentException("unknown segment type")
        }
        i++
    }
    return querySegments
}

fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is PGobject -> runCatching { Json.parseToJsonElement(value.value ?: "null") }
        .getOrElse { JsonPrimitive(value.value ?: "null") }
    is String -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is UUID -> JsonPrimitive(value.toString())
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in value) {
            put(k.toString(), anyToJsonElement(v))
        }
    }

    is List<*> -> buildJsonArray {
        for (v in value) {
            add(anyToJsonElement(v))
        }
    }

    else -> JsonPrimitive(value.toString())
}

/**
 * Native query per active driver plus the routing decision, without executing anything.
 */
fun buildExplainResponse(query: GetQuery): String {
    val routedDriver = runCatching { DriverManager.chooseDriverModel(query) }

    return buildJsonObject {
        put(
            "routedDriver", routedDriver.getOrNull()?.let {
                JsonPrimitive(
                    when (it) {
                        is PostgresDriver -> "postgres"
                        is MongoDriver -> "mongo"
                        is Neo4jDriver -> "neo4j"
                        else -> "unknown"
                    }
                )
            } ?: JsonNull)
        routedDriver.exceptionOrNull()?.let {
            put("routingError", it.message ?: it.javaClass.simpleName)
        }
        put("queries", buildJsonObject {
            DriverManager.postgresDriver?.let { driver ->
                put("postgres", buildJsonArray { for (q in driver.explain(query)) add(JsonPrimitive(q)) })
            }
            DriverManager.mongoDriver?.let { driver ->
                put("mongo", buildJsonArray { for (q in driver.explain(query)) add(JsonPrimitive(q)) })
            }
            DriverManager.neo4jDriver?.let { driver ->
                put("neo4j", buildJsonArray { for (q in driver.explain(query)) add(JsonPrimitive(q)) })
            }
        })
    }.toString()
}

/**
 * Raw stored representation of one instance per active driver. Inactive drivers are null.
 */
fun buildRawInstanceResponse(collection: CollectionModel, uuid: UUID): String {
    return buildJsonObject {
        val postgres = DriverManager.postgresDriver
        if (postgres == null) {
            put("postgres", JsonNull)
        } else {
            put("postgres", buildJsonObject {
                put("tables", buildJsonObject {
                    for ((table, rows) in postgres.getRawInstance(collection, uuid)) {
                        put(table, buildJsonArray {
                            for (row in rows) {
                                add(anyToJsonElement(row))
                            }
                        })
                    }
                })
            })
        }

        val mongo = DriverManager.mongoDriver
        if (mongo == null) {
            put("mongo", JsonNull)
        } else {
            val (ownDocument, parentDocument) = mongo.getRawInstance(collection, uuid)
            put("mongo", buildJsonObject {
                put("document", anyToJsonElement(ownDocument))
                put("parentDocument", anyToJsonElement(parentDocument))
            })
        }

        val neo4j = DriverManager.neo4jDriver
        if (neo4j == null) {
            put("neo4j", JsonNull)
        } else {
            put("neo4j", anyToJsonElement(neo4j.getRawInstance(collection.name, uuid)))
        }
    }.toString()
}

/**
 * Static poly-field -> physical-location mapping for one collection, derived from the
 * registered schema and the drivers' naming conventions. No query execution involved.
 */
fun buildMappingResponse(collection: CollectionModel): String {
    val connections = DatabaseManager.listConnections().filter {
        it.collection1Name == collection.name || it.collection2Name == collection.name
    }

    return buildJsonObject {
        put("collection", collection.name)

        put("postgres", buildJsonObject {
            put("table", "ps_col_${collection.name}")
            put("idColumn", "_id")
            put("dynamicDataColumn", "_dynamic_data")
            put("parentTable", collection.parentCollection?.let { JsonPrimitive("ps_col_$it") } ?: JsonNull)
            put(
                "parentFkColumn",
                if (collection.parentCollection != null) JsonPrimitive("ps_parent_fk") else JsonNull)
            put("fields", buildJsonObject {
                for ((field, type) in collection.schema) {
                    put(field, buildJsonObject {
                        put("column", field)
                        put("type", type.toPostgresType())
                    })
                }
            })
            put("connectionTables", buildJsonObject {
                for (connection in connections) {
                    val otherCollection =
                        if (connection.collection1Name == collection.name) connection.collection2Name
                        else connection.collection1Name
                    put(connection.name, buildJsonObject {
                        put("table", "ps_con_${connection.name}")
                        put("fkColumnThisSide", "ps_cfk_${collection.name}")
                        put("fkColumnOtherSide", "ps_cfk_$otherCollection")
                    })
                }
            })
        })

        put("mongo", buildJsonObject {
            put("collection", collection.name)
            put(
                "embeddedInParentAs",
                collection.parentCollection?.let { JsonPrimitive("_sub_${collection.name}") } ?: JsonNull)
            put("parentCollection", collection.parentCollection?.let { JsonPrimitive(it) } ?: JsonNull)
            put("idKey", "_id")
            put("fields", buildJsonObject {
                for ((field, _) in collection.schema) {
                    put(field, buildJsonObject { put("key", field) })
                }
            })
            put("subcollectionArrays", buildJsonObject {
                for (child in collection.childCollections) {
                    put(child, "_sub_$child")
                }
            })
            put("connectionArrays", buildJsonObject {
                for (connection in connections) {
                    put(connection.name, "_con_${connection.name}")
                }
            })
        })

        put("neo4j", buildJsonObject {
            put("label", "ps_col_${collection.name}")
            put("idProperty", "_id")
            put(
                "parentRelationship",
                if (collection.parentCollection != null) JsonPrimitive("ps_parent_of") else JsonNull)
            put("fields", buildJsonObject {
                for ((field, _) in collection.schema) {
                    put(field, buildJsonObject { put("property", field) })
                }
            })
            put("connections", buildJsonObject {
                for (connection in connections) {
                    val outgoing = connection.collection1Name == collection.name
                    put(connection.name, buildJsonObject {
                        put("relationshipType", connection.name)
                        put("direction", if (outgoing) "outgoing" else "incoming")
                        put(
                            "otherCollection",
                            if (outgoing) connection.collection2Name else connection.collection1Name
                        )
                    })
                }
            })
        })

        put("connectionData", buildJsonObject {
            for (connection in connections) {
                put(connection.name, buildJsonObject {
                    for ((field, type) in connection.connectionDataSchema) {
                        put(field, buildJsonObject {
                            put("postgresColumn", field)
                            put("mongoKey", "_rel.$field")
                            put("neo4jProperty", field)
                            put("type", type.name)
                        })
                    }
                })
            }
        })
    }.toString()
}
