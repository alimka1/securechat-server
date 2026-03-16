package com.securechat.server.routes

import com.securechat.server.Security
import com.securechat.server.dto.BackupRestoreResponse
import com.securechat.server.dto.BackupUploadRequest
import com.securechat.server.dto.BackupUploadResponse
import com.securechat.server.models.AccountBackups
import com.securechat.server.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.accountBackupRoutes() {

    route("/backup") {

        post("/upload") {
            val principal = call.principal<JWTPrincipal>()!!
            val jwtUserId = Security.userId(principal)

            val body = try {
                call.receive<BackupUploadRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { userId, encryptedBackupBlob, backupVersion, clientUpdatedAt }"),
                )
                return@post
            }

            val requestUserId = body.userId.trim()
            if (requestUserId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("userId must not be blank"),
                )
                return@post
            }

            if (requestUserId != jwtUserId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("userId mismatch"),
                )
                return@post
            }

            if (body.encryptedBackupBlob.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("encryptedBackupBlob must not be blank"),
                )
                return@post
            }

            if (body.backupVersion < 1) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("backupVersion must be >= 1"),
                )
                return@post
            }

            if (body.clientUpdatedAt <= 0L) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("clientUpdatedAt must be > 0"),
                )
                return@post
            }

            transaction {
                val existing = AccountBackups
                    .select { AccountBackups.userId eq requestUserId }
                    .firstOrNull()

                if (existing == null) {
                    AccountBackups.insert {
                        it[userId] = requestUserId
                        it[encryptedBackupBlob] = body.encryptedBackupBlob
                        it[backupVersion] = body.backupVersion
                        it[clientUpdatedAt] = body.clientUpdatedAt
                        it[createdAt] = Clock.System.now()
                        it[updatedAt] = Clock.System.now()
                    }
                } else {
                    AccountBackups.update({ AccountBackups.userId eq requestUserId }) {
                        it[encryptedBackupBlob] = body.encryptedBackupBlob
                        it[backupVersion] = body.backupVersion
                        it[clientUpdatedAt] = body.clientUpdatedAt
                        it[updatedAt] = Clock.System.now()
                    }
                }
            }

            call.respond(
                HttpStatusCode.OK,
                BackupUploadResponse(status = "ok"),
            )
        }

        post("/restore") {
            val principal = call.principal<JWTPrincipal>()!!
            val jwtUserId = Security.userId(principal)

            val row = transaction {
                AccountBackups
                    .select { AccountBackups.userId eq jwtUserId }
                    .orderBy(AccountBackups.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
            }

            if (row == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("No backup found"),
                )
                return@post
            }

            val response = BackupRestoreResponse(
                userId = row[AccountBackups.userId],
                encryptedBackupBlob = row[AccountBackups.encryptedBackupBlob],
                backupVersion = row[AccountBackups.backupVersion],
                clientUpdatedAt = row[AccountBackups.clientUpdatedAt],
            )

            call.respond(response)
        }
    }
}

