package com.securechat.server.routes

import com.securechat.server.auth.AuthService
import com.securechat.server.auth.AuthTokenService
import com.securechat.server.dto.AuthResponse
import com.securechat.server.dto.LoginRequest
import com.securechat.server.dto.RefreshRequest
import com.securechat.server.dto.RegisterRequest
import com.securechat.server.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(
    authService: AuthService,
    tokenService: AuthTokenService,
) {
    route("/auth") {

        post("/register") {
            val body = try {
                call.receive<RegisterRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { username, password }"),
                )
                return@post
            }

            val username = body.username.trim()
            val password = body.password

            if (username.isBlank() || password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("username and password must not be blank"),
                )
                return@post
            }

            if (username.length > 64) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("username is too long"),
                )
                return@post
            }

            if (password.length < 8) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("password must be at least 8 characters"),
                )
                return@post
            }

            try {
                val user = authService.register(
                    username = username,
                    password = password,
                )
                val tokens = tokenService.generateTokens(
                    userId = user.userId,
                    username = user.username,
                )
                call.respond(
                    AuthResponse(
                        userId = tokens.userId,
                        username = tokens.username,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAt = tokens.expiresAt,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(e.message ?: "Invalid registration data"),
                )
            } catch (e: Exception) {
                if (e.message == "Username already exists") {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("Username already exists"),
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Registration failed"),
                    )
                }
            }
        }

        post("/login") {
            val body = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { username, password }"),
                )
                return@post
            }

            val username = body.username.trim()
            val password = body.password

            if (username.isBlank() || password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("username and password must not be blank"),
                )
                return@post
            }

            try {
                val user = authService.login(
                    username = username,
                    password = password,
                )
                val tokens = tokenService.generateTokens(
                    userId = user.userId,
                    username = user.username,
                )
                call.respond(
                    AuthResponse(
                        userId = tokens.userId,
                        username = tokens.username,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAt = tokens.expiresAt,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(e.message ?: "Invalid login data"),
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid username or password"),
                )
            }
        }

        post("/refresh") {
            val body = try {
                call.receive<RefreshRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { refreshToken }"),
                )
                return@post
            }

            val refreshToken = body.refreshToken.trim()
            if (refreshToken.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("refreshToken must not be blank"),
                )
                return@post
            }

            try {
                val tokens = tokenService.refreshTokens(refreshToken)
                call.respond(
                    AuthResponse(
                        userId = tokens.userId,
                        username = tokens.username,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAt = tokens.expiresAt,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid or expired refresh token"),
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid or expired refresh token"),
                )
            }
        }
    }
}

