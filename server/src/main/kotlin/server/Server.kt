package ch.flavianz.server

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.query.QueryPath
import ch.flavianz.query.QuerySegment
import ch.flavianz.query.GetQuery
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun startServer() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost() // fine for development
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }
        routing {
            post("/collection/create") {
                val body = runCatching { call.receive<CreateCollectionRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                }

                val result = runCatching {
                    DatabaseManager.createCollection(
                        body.name,
                        body.fields.associate { it.name to DataType.valueOf(it.type.uppercase()) },
                        body.parentCollection
                    )
                }

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Collection '${body.name}' created") },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed") }
                )
            }
            post("/collection/drop") {
                val body = runCatching { call.receive<DropCollectionRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                }

                val result = runCatching {
                    DatabaseManager.dropCollection(
                        body.name,
                        body.recursive
                    )
                }

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Collection '${body.name}' dropped") },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed") }
                )
            }
            post("/connection/create") {
                val body = runCatching { call.receive<CreateConnectionRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                }

                val result = runCatching {
                    DatabaseManager.createConnection(
                        ConnectionModel(
                            body.name, body.collection1Name, body.collection2Name,
                            body.fields.associate { it.name to DataType.valueOf(it.type.uppercase()) },
                        )
                    )
                }

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Connection '${body.name}' created") },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed") }
                )
            }

            post("/connection/drop") {
                val body = runCatching { call.receive<DropConnectionRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                }

                val result = runCatching {
                    DatabaseManager.dropConnection(
                        body.name
                    )
                }

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Connection '${body.name}' dropped") },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed") }
                )
            }

            post("/document/insert") {
                val body = call.receive<InsertDocumentRequest>()

                val polyFields = body.fields.mapValues { (_, v) -> v.toPolyValue() }

                val result = runCatching {
                    DatabaseManager.insertDocument(
                        body.collection,
                        polyFields.toMap(),
                        body.parentDocUuid?.let { UUID.fromString(it) })
                }


                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Inserted document with UUID $it") },
                    onFailure = {
                        call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed")
                        print(it)
                        print(it.stackTraceToString())
                    }
                )
            }

            post("/connection/insert") {
                val body = call.receive<InsertConnectionRequest>()

                val polyFields = body.fields.mapValues { (_, v) -> v.toPolyValue() }

                val result = runCatching {
                    val connection = DatabaseManager.getConnectionModel(body.connection)
                    assert(connection.collection1Name == body.collection1Name || connection.collection1Name == body.collection2Name)
                    assert(connection.collection2Name == body.collection1Name || connection.collection2Name == body.collection2Name)
                    DatabaseManager.insertConnection(
                        body.connection,
                        connection.collection1Name,
                        UUID.fromString(
                            if (connection.collection1Name == body.collection1Name)
                                body.collection1Uuid else body.collection2Uuid
                        ),
                        connection.collection2Name,
                        UUID.fromString(
                            if (connection.collection2Name == body.collection2Name)
                                body.collection2Uuid else body.collection1Uuid
                        ),
                        polyFields.toMap(),
                    )
                }


                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, "Inserted connection with UUID $it") },
                    onFailure = {
                        call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed")
                        print(it)
                        print(it.stackTraceToString())
                    }
                )
            }
            post("/query/take") {
                val body = call.receive<QueryRequest>()

                val querySegments = mutableListOf<QuerySegment>()

                var i = 0
                while (i < body.path.size) {
                    val requestSegment = body.path[i]
                    when (requestSegment.type) {
                        "collection" -> querySegments.add(
                            QuerySegment.Collection(
                                requestSegment.name,
                                ConditionParser(requestSegment.condition).parse()
                            )
                        )

                        "connection" -> {
                            require(i + 1 < body.path.size)
                            val nextSegment = body.path[i + 1]
                            require(nextSegment.type == "collection")
                            querySegments.add(
                                QuerySegment.Connection(
                                    requestSegment.name,
                                    nextSegment.name,
                                    if (!requestSegment.condition.isNullOrEmpty()) ConditionParser(requestSegment.condition).parse() else null,
                                    if (!nextSegment.condition.isNullOrEmpty()) ConditionParser(nextSegment.condition).parse() else null
                                )
                            )
                            i++
                        }

                        else -> throw IllegalArgumentException("unknown segment type")
                    }
                    i++
                }

                val getQuery = GetQuery(QueryPath(querySegments))

                val result = runCatching {
                    DatabaseManager.get(
                        getQuery
                    )
                }


                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it.toJson()) },
                    onFailure = {
                        println("Query failed: ${it.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            buildJsonObject { put("message", it.message); put("stack_trace", it.stackTraceToString()) })
                        print(it)
                        print(it.stackTraceToString())
                    }
                )
            }
            /*post("/query/bench") {
                val body = call.receive<TakeRequest>()

                val querySegments = mutableListOf<QuerySegment>()

                var i = 0
                while (i < body.path.size) {
                    val requestSegment = body.path[i]
                    when (requestSegment.type) {
                        "collection" -> querySegments.add(
                            QuerySegment.Collection(
                                requestSegment.name,
                                if (!requestSegment.condition.isNullOrEmpty()) ConditionParser(requestSegment.condition).parse() else null
                            )
                        )

                        "connection" -> {
                            require(i + 1 < body.path.size)
                            val nextSegment = body.path[i + 1]
                            require(nextSegment.type == "collection")
                            querySegments.add(
                                QuerySegment.Connection(
                                    requestSegment.name,
                                    nextSegment.name,
                                    if (!requestSegment.condition.isNullOrEmpty()) ConditionParser(requestSegment.condition).parse() else null,
                                    if (!nextSegment.condition.isNullOrEmpty()) ConditionParser(nextSegment.condition).parse() else null
                                )
                            )
                            i++
                        }

                        else -> throw IllegalArgumentException("unknown segment type")
                    }
                    i++
                }
                val fieldRefs = (body.take?.flatMap { segment -> segment.value.map { FieldRef.Named(segment.key, it) } }
                    ?: emptyList()) +
                        (body.collect?.map { FieldRef.Wildcard(it) } ?: emptyList())

                val polyQuery = PolyQuery(QueryPath(querySegments), PolyTerminal.Take(fieldRefs))

                val result = runCatching {
                    DriverManager.benchmarkTake(polyQuery, polyQuery.terminal as PolyTerminal.Take)
                }


                result.fold(
                    onSuccess = {
                        call.respond(HttpStatusCode.OK)
                    },
                    onFailure = {
                        println("Query failed: ${it.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            buildJsonObject { put("message", it.message); put("stack_trace", it.stackTraceToString()) })
                        print(it)
                        print(it.stackTraceToString())
                    }
                )
            }*/
            get("/collections/list") {
                val collections = DatabaseManager.listCollections()
                call.respond(HttpStatusCode.OK, collections)
            }
            get("/connections/list") {
                val connections = DatabaseManager.listConnections()
                call.respond(HttpStatusCode.OK, connections)
            }
            get("/schema") {
                val schema = DatabaseManager.getSchema()
                call.respond(HttpStatusCode.OK, schema)
            }
            get("/version") {
                call.respond(HttpStatusCode.OK, "0.0.1")
            }
        }
    }.start(wait = true)
}