package ch.flavianz.server

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.query.QueryParser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
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

fun startServer() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }
        routing {
            get("/query") {
                val queryString = call.request.queryParameters["q"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing 'q'")

                val result = runCatching {
                    DatabaseManager.query(QueryParser(queryString).parse())
                }

                result.fold(
                    onSuccess = {
                        call.response.headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        call.respond(it.toJson())
                    },
                    onFailure = {
                        call.respond(HttpStatusCode.InternalServerError, it.message ?: "Query failed")
                    }
                )
            }
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
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "Failed") }
                )
            }
        }
    }.start(wait = true)
}

