package com.securechat.server.routes

import com.securechat.server.Security
import com.securechat.server.dto.ProfileUpdateRequest
import com.securechat.server.dto.UserProfileResponse
import com.securechat.server.models.AuthUsers
import com.securechat.server.models.ErrorResponse
import com.securechat.server.models.Profiles
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.profileRoutes() {

    route("/profile") {

        get("/me") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val (authRow, profileRow) = transaction {
                val auth = AuthUsers
                    .select { AuthUsers.userId eq userId }
                    .firstOrNull()
                    ?: return@transaction Pair(null, null)

                val profile = Profiles
                    .select { Profiles.userId eq userId }
                    .firstOrNull()

                auth to profile
            }

            if (authRow == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("User not found"),
                )
                return@get
            }

            val createdAtInstant = authRow[AuthUsers.createdAt]
            val createdAtMillis = createdAtInstant.toJavaInstant().toEpochMilli()

            val response = UserProfileResponse(
                userId = authRow[AuthUsers.userId],
                username = authRow[AuthUsers.username],
                displayName = profileRow?.get(Profiles.displayName),
                avatarUrl = profileRow?.get(Profiles.avatarUrl),
                createdAt = createdAtMillis,
            )

            call.respond(response)
        }

        patch("/me") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val body = try {
                call.receive<ProfileUpdateRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { displayName?, avatarUrl? }"),
                )
                return@patch
            }

            val newDisplayName = body.displayName?.trim()
            val newAvatarUrl = body.avatarUrl?.trim()

            if ((newDisplayName == null || newDisplayName.isBlank()) &&
                (newAvatarUrl == null || newAvatarUrl.isBlank())
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("At least one of displayName or avatarUrl must be provided"),
                )
                return@patch
            }

            transaction {
                val existing = Profiles
                    .select { Profiles.userId eq userId }
                    .firstOrNull()

                if (existing == null) {
                    Profiles.insert {
                        it[Profiles.userId] = userId
                        if (!newDisplayName.isNullOrBlank()) {
                            it[displayName] = newDisplayName
                        }
                        if (!newAvatarUrl.isNullOrBlank()) {
                            it[avatarUrl] = newAvatarUrl
                        }
                    }
                } else {
                    Profiles.update({ Profiles.userId eq userId }) {
                        if (!newDisplayName.isNullOrBlank()) {
                            it[displayName] = newDisplayName
                        }
                        if (!newAvatarUrl.isNullOrBlank()) {
                            it[avatarUrl] = newAvatarUrl
                        }
                    }
                }
            }

            // Return updated profile
            val (authRow, profileRow) = transaction {
                val auth = AuthUsers
                    .select { AuthUsers.userId eq userId }
                    .firstOrNull()
                    ?: return@transaction Pair(null, null)

                val profile = Profiles
                    .select { Profiles.userId eq userId }
                    .firstOrNull()

                auth to profile
            }

            if (authRow == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("User not found"),
                )
                return@patch
            }

            val createdAtInstant = authRow[AuthUsers.createdAt]
            val createdAtMillis = createdAtInstant.toJavaInstant().toEpochMilli()

            val response = UserProfileResponse(
                userId = authRow[AuthUsers.userId],
                username = authRow[AuthUsers.username],
                displayName = profileRow?.get(Profiles.displayName),
                avatarUrl = profileRow?.get(Profiles.avatarUrl),
                createdAt = createdAtMillis,
            )

            call.respond(response)
        }
    }
}

