package com.securechat.server

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

private val jsonFormat = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun main() {
    initDatabase()
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {

    install(ContentNegotiation) { json(jsonFormat) }

    // Для Android-клиента CORS не нужен. Оставляем для dev.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete)
        allowCredentials = false
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(Security.verifier())
            validate { cred ->
                cred.payload.getClaim("userId").asString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { JWTPrincipal(cred.payload) }
            }
        }
    }

    install(WebSockets)

    configureRouting(jsonFormat)
}
