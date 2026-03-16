package com.securechat.server.routes

import com.securechat.server.Security
import com.securechat.server.backup.AccountBackupService
import com.securechat.server.dto.BackupRestoreResponse
import com.securechat.server.dto.BackupUploadRequest
import com.securechat.server.dto.BackupUploadResponse
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

fun Route.accountBackupRoutes(
    accountBackupService: AccountBackupService,
) {

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

            accountBackupService.upsertEncryptedBackup(
                userId = requestUserId,
                encryptedBackupBlob = body.encryptedBackupBlob,
                backupVersion = body.backupVersion,
                clientUpdatedAt = body.clientUpdatedAt,
            )

            call.respond(
                HttpStatusCode.OK,
                BackupUploadResponse(status = "ok"),
            )
        }

        post("/restore") {
            val principal = call.principal<JWTPrincipal>()!!
            val jwtUserId = Security.userId(principal)

            val row = accountBackupService.getEncryptedBackup(jwtUserId)

            if (row == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("No backup found"),
                )
                return@post
            }

            val response = BackupRestoreResponse(
                userId = row.userId,
                encryptedBackupBlob = row.encryptedBackupBlob,
                backupVersion = row.backupVersion,
                clientUpdatedAt = row.clientUpdatedAt,
            )

            call.respond(response)
        }
    }
}

