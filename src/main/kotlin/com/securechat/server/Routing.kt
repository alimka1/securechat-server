package com.securechat.server

import com.auth0.jwt.JWT
import com.securechat.server.auth.AuthService
import com.securechat.server.auth.AuthTokenService
import com.securechat.server.backup.AccountBackupService
import com.securechat.server.call.CallSignalingService
import com.securechat.server.dto.AuthResponse
import com.securechat.server.models.*
import com.securechat.server.routes.authRoutes
import com.securechat.server.routes.accountBackupRoutes
import com.securechat.server.routes.profileRoutes
import com.securechat.server.chat.ChatService
import com.securechat.server.contact.ContactInviteService
import com.securechat.server.routes.chatRoutes
import com.securechat.server.routes.contactInviteRoutes
import com.securechat.server.realtime.ChatRealtimeService
import com.securechat.server.realtime.PresenceService
import com.securechat.server.realtime.RealtimeCommandService
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.Json

fun Application.configureRouting(json: Json) {

    // Backup directory
    val backupDir = File("backups").apply { mkdirs() }

    val authService = AuthService()
    val authTokenService = AuthTokenService()
    val chatService = ChatService()
    val contactInviteService = ContactInviteService()
    val accountBackupService = AccountBackupService()
    val chatRealtimeService = ChatRealtimeService(json)
    val presenceService = PresenceService(chatService, chatRealtimeService)
    val callSignalingService = CallSignalingService(chatService, chatRealtimeService)
    val realtimeCommandService = RealtimeCommandService(json, presenceService, callSignalingService)

    // One-time invite storage: code -> InviteEntry
    data class InviteEntry(val ownerUserId: String, val expiresAt: Long, var used: Boolean = false)
    val invites = mutableMapOf<String, InviteEntry>()

    routing {

        authRoutes(authService, authTokenService)

        get("/") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        post("/register") {
            try {
                val body = try {
                    call.receive<AuthRequest>()
                } catch (e: Exception) {
                    call.application.log.info("Register attempt: invalid request body")
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { username, password }"))
                    return@post
                }
                val username = body.username.trim()
                val password = body.password
                call.application.log.info("Register attempt: username=$username")
                if (username.isBlank() || password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("username and password must not be blank"))
                    return@post
                }
                if (username.length > 64) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("username is too long"))
                    return@post
                }
                if (password.length < 8) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("password must be at least 8 characters"))
                    return@post
                }
                val user = authService.register(username = username, password = password)
                val tokens = authTokenService.generateTokens(userId = user.userId, username = user.username)
                call.respond(
                    AuthResponse(
                        userId = tokens.userId,
                        username = tokens.username,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAt = tokens.expiresAt,
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid registration data"))
            } catch (e: Exception) {
                if (e.message == "Username already exists") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Username already exists"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Registration failed"))
                }
            }
        }

        post("/login") {
            try {
                val body = try {
                    call.receive<AuthRequest>()
                } catch (e: Exception) {
                    call.application.log.info("Login attempt: invalid request body")
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { username, password }"))
                    return@post
                }
                val username = body.username.trim()
                val password = body.password
                call.application.log.info("Login attempt: username=$username")
                if (username.isBlank() || password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("username and password must not be blank"))
                    return@post
                }
                val user = authService.login(username = username, password = password)
                val tokens = authTokenService.generateTokens(userId = user.userId, username = user.username)
                call.respond(
                    AuthResponse(
                        userId = tokens.userId,
                        username = tokens.username,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAt = tokens.expiresAt,
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid login data"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password"))
            }
        }

        post("/invites/create") {
            try {
                val body = call.receive<InviteCreateRequest>()
                val ownerUserId = body.userId.trim()
                if (ownerUserId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("userId must not be blank"))
                    return@post
                }
                val code = UUID.randomUUID().toString().replace("-", "").take(16)
                val expiresAt = System.currentTimeMillis() + 5 * 60 * 1000
                invites[code] = InviteEntry(ownerUserId = ownerUserId, expiresAt = expiresAt)
                call.application.log.info("Invite create: code=$code ownerUserId=$ownerUserId")
                call.respond(InviteCreateResponse(code = code, expiresAt = expiresAt))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { userId }"))
            }
        }

        post("/invites/consume") {
            try {
                val body = call.receive<InviteConsumeRequest>()
                val code = body.code.trim()
                if (code.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("code must not be blank"))
                    return@post
                }
                val entry = invites[code]
                if (entry == null) {
                    call.application.log.info("Invite consume: code=$code result=not_found")
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Invalid code"))
                    return@post
                }
                if (entry.used) {
                    call.application.log.info("Invite consume: code=$code ownerUserId=${entry.ownerUserId} result=already_used")
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Already used"))
                    return@post
                }
                if (System.currentTimeMillis() > entry.expiresAt) {
                    call.application.log.info("Invite consume: code=$code ownerUserId=${entry.ownerUserId} result=expired")
                    call.respond(HttpStatusCode.Gone, ErrorResponse("Expired"))
                    return@post
                }
                entry.used = true
                call.application.log.info("Invite consume: code=$code ownerUserId=${entry.ownerUserId} result=success")
                call.respond(InviteConsumeResponse(ownerUserId = entry.ownerUserId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { code }"))
            }
        }

        // MVP auth: принимает userId в plain text и выдаёт JWT
        // В production заменим на регистрацию + device proof.
        post("/auth") {
            val userId = call.receiveText().trim()
            if (userId.isBlank() || userId.length > 64) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid userId"))
                return@post
            }

            // ensure user exists
            transaction {
                Users.insertIgnore {
                    it[Users.userId] = userId
                }
            }

            val expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
            val token = JWT.create()
                .withAudience(Security.AUD)
                .withIssuer(Security.ISS)
                .withClaim("userId", userId)
                .withExpiresAt(Date(expiresAt))
                .sign(Security.algorithm())

            call.respond(AuthResponse(userId = userId, username = userId, accessToken = token, refreshToken = token, expiresAt = expiresAt))
        }

        authenticate("auth-jwt") {

            accountBackupRoutes(accountBackupService)
            profileRoutes()
            chatRoutes(chatService, chatRealtimeService)
            contactInviteRoutes(contactInviteService, chatService)

            webSocket("/ws/chat") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = Security.userId(principal)

                chatRealtimeService.registerConnection(userId, this)
                presenceService.onConnected(userId)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            runCatching {
                                realtimeCommandService.handleTextCommand(userId, text)
                            }
                        }
                    }
                } finally {
                    runCatching { callSignalingService.onUserDisconnected(userId) }
                    chatRealtimeService.removeConnection(userId, this)
                    presenceService.onDisconnected(userId)
                }
            }

            // --- PreKeys publish (client uploads its bundle) ---
            post("/prekeys/publish") {
                val principal = call.principal<JWTPrincipal>()!!
                val jwtUserId = Security.userId(principal)

                val body = call.receive<PublishPreKeyRequest>()

                if (body.userId != jwtUserId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("userId mismatch"))
                    return@post
                }

                if (body.userId.isBlank() || body.deviceId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing userId/deviceId"))
                    return@post
                }

                // persist user + device + latest signed prekey
                transaction {
                    Users.insertIgnore { it[userId] = body.userId }

                    Devices.insertIgnore {
                        it[deviceId] = body.deviceId
                        it[userId] = body.userId
                        it[deviceKey] = body.deviceKey
                    }

                    // Insert new prekey row (you can also "upsert" per device)
                    PreKeys.insert {
                        it[id] = UUID.randomUUID().toString()
                        it[userId] = body.userId
                        it[deviceId] = body.deviceId
                        it[identityKey] = body.identityKey
                        it[signedPreKeyId] = body.signedPreKeyId
                        it[signedPreKey] = body.signedPreKey
                        it[signedPreKeySig] = body.signedPreKeySig
                    }

                    // one-time prekeys
                    body.oneTimePreKeys.forEach { otk ->
                        OneTimePreKeys.insert {
                            it[id] = UUID.randomUUID().toString()
                            it[userId] = body.userId
                            it[deviceId] = body.deviceId
                            it[keyId] = otk.keyId
                            it[publicKey] = otk.publicKey
                            it[used] = false
                        }
                    }
                }

                call.respond(mapOf("status" to "ok"))
            }

            // --- Get prekey bundle for recipient (X3DH) ---
            // GET /prekeys/bundle/{userId}?deviceId=...
            get("/prekeys/bundle/{userId}") {
                val targetUserId = call.parameters["userId"]?.trim().orEmpty()
                if (targetUserId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing userId"))
                    return@get
                }

                val targetDeviceId = call.request.queryParameters["deviceId"]?.trim()

                val bundle = transaction {
                    // pick latest signed prekey for device (or any device)
                    val prekeyRow = if (!targetDeviceId.isNullOrBlank()) {
                        PreKeys.select { (PreKeys.userId eq targetUserId) and (PreKeys.deviceId eq targetDeviceId) }
                            .orderBy(PreKeys.createdAt, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    } else {
                        PreKeys.select { PreKeys.userId eq targetUserId }
                            .orderBy(PreKeys.createdAt, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    } ?: return@transaction null

                    val devId = prekeyRow[PreKeys.deviceId]

                    // pick one unused one-time prekey and mark used
                    val otkRow = OneTimePreKeys
                        .select { (OneTimePreKeys.userId eq targetUserId) and (OneTimePreKeys.deviceId eq devId) and (OneTimePreKeys.used eq false) }
                        .limit(1)
                        .firstOrNull()

                    var otkId: Int? = null
                    var otkPub: String? = null

                    if (otkRow != null) {
                        otkId = otkRow[OneTimePreKeys.keyId]
                        otkPub = otkRow[OneTimePreKeys.publicKey]

                        OneTimePreKeys.update({ OneTimePreKeys.id eq otkRow[OneTimePreKeys.id] }) {
                            it[used] = true
                        }
                    }

                    PreKeyBundleResponse(
                        userId = targetUserId,
                        deviceId = devId,
                        identityKey = prekeyRow[PreKeys.identityKey],
                        signedPreKeyId = prekeyRow[PreKeys.signedPreKeyId],
                        signedPreKey = prekeyRow[PreKeys.signedPreKey],
                        signedPreKeySig = prekeyRow[PreKeys.signedPreKeySig],
                        oneTimePreKeyId = otkId,
                        oneTimePreKey = otkPub
                    )
                }

                if (bundle == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No prekeys for user"))
                } else {
                    call.respond(bundle)
                }
            }

            // --- Backup upload (encrypted blob only) ---
            // multipart field name: backupFile
            post("/backup") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = Security.userId(principal)

                val multipart = call.receiveMultipart()
                var storedFileName: String? = null

                multipart.forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && part.name == "backupFile") {
                            val fileName = "${UUID.randomUUID()}.enc"
                            val file = File(backupDir, fileName)

                            part.streamProvider().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            storedFileName = fileName
                        }
                    } finally {
                        part.dispose()
                    }
                }

                val fileName = storedFileName
                if (fileName == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file uploaded"))
                    return@post
                }

                transaction {
                    Backups.insert {
                        it[id] = UUID.randomUUID().toString()
                        it[Backups.userId] = userId
                        it[filePath] = fileName
                    }
                }

                call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
            }

            // --- Backup download latest ---
            get("/backup/latest") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = Security.userId(principal)

                val latest = transaction {
                    Backups.select { Backups.userId eq userId }
                        .orderBy(Backups.createdAt, SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                }

                if (latest == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No backup found"))
                    return@get
                }

                val fileName = latest[Backups.filePath]
                val file = File(backupDir, fileName)

                if (!file.exists() || !file.isFile) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Backup file missing"))
                    return@get
                }

                call.respondFile(file)
            }
        }
    }
}
