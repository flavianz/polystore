package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.query.QueryParser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun startServer() {
    embeddedServer(Netty, port = 8080) {
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
        }
    }.start(wait = true)
}