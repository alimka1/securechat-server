package com.securechat.server

import com.auth0.jwt.JWT
import com.securechat.server.models.*
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
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
private suspend fun handleWsSession(
    userId: String,
    session: DefaultWebSocketServerSession,
    connections: ConcurrentHashMap<String, DefaultWebSocketServerSession>,
    json: Json,
    log: (String) -> Unit
) {
    connections[userId] = session
    log("WebSocket connect: userId=$userId")

    try {
        for (frame in session.incoming) {
            try {
                if (frame is Frame.Text) {
                    val raw = frame.readText()

                    val msg = runCatching { json.decodeFromString(SignalMessage.serializer(), raw) }.getOrNull()
                        ?: continue

                    val to = msg.to?.trim().orEmpty()
                    if (to.isBlank()) {
                        log("Signal: msg.to is blank, ignoring")
                        continue
                    }

                    log("IN from=$userId to=$to type=${msg.type} payloadLen=${msg.payload.length}")

                    val targetSession = connections[to]
                    if (targetSession == null) {
                        log("DROP target_not_connected to=$to")
                        continue
                    }

                    try {
                        targetSession.send(
                            Frame.Text(
                                json.encodeToString(
                                    SignalMessage.serializer(),
                                    msg.copy(from = userId)
                                )
                            )
                        )
                        log("OUT to=$to ok")
                    } catch (e: Exception) {
                        log("WS send failed to=$to reason=${e.message}")
                        connections.remove(to)
                    }
                }
            } catch (e: Exception) {
                log("WS frame error: ${e.message}")
            }
        }
    } finally {
        connections.remove(userId)
        log("WebSocket disconnect: userId=$userId")
    }
}

fun Application.configureRouting(json: Json) {

    // WebSocket connections per userId
    val connections = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // Backup directory
    val backupDir = File("backups").apply { mkdirs() }

    // In-memory auth storage: userId -> password
    val users = mutableMapOf<String, String>()

    // One-time invite storage: code -> InviteEntry
    data class InviteEntry(val ownerUserId: String, val expiresAt: Long, var used: Boolean = false)
    val invites = mutableMapOf<String, InviteEntry>()

    routing {

        get("/") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        post("/register") {
            try {
                val body = try {
                    call.receive<AuthRequest>()
                } catch (e: Exception) {
                    call.application.log.info("Register attempt: invalid request body")
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { userId, password }"))
                    return@post
                }
                val userId = body.userId.trim()
                val password = body.password
                call.application.log.info("Register attempt: userId=$userId")
                if (userId.isBlank() || password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("userId and password must not be blank"))
                    return@post
                }
                if (users.containsKey(userId)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("User already exists"))
                    return@post
                }
                users[userId] = password
                call.respond(AuthResponse("token_$userId"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Registration failed"))
            }
        }

        post("/login") {
            try {
                val body = try {
                    call.receive<AuthRequest>()
                } catch (e: Exception) {
                    call.application.log.info("Login attempt: invalid request body")
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body. Expected JSON: { userId, password }"))
                    return@post
                }
                val userId = body.userId.trim()
                val password = body.password
                call.application.log.info("Login attempt: userId=$userId")
                val storedPassword = users[userId]
                if (storedPassword == null || storedPassword != password) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid userId or password"))
                    return@post
                }
                call.respond(AuthResponse("token_$userId"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Login failed"))
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

            val token = JWT.create()
                .withAudience(Security.AUD)
                .withIssuer(Security.ISS)
                .withClaim("userId", userId)
                .sign(Security.algorithm())

            call.respond(AuthResponse(token))
        }

        // --- WebSocket alias for Android client: /ws?token=token_<userId> ---
        webSocket("/ws") {
            val token = call.request.queryParameters["token"]
            if (token.isNullOrBlank() || !token.startsWith("token_")) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing token"))
                return@webSocket
            }
            val userId = token.removePrefix("token_").trim()
            if (userId.isBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))
                return@webSocket
            }
            handleWsSession(userId, this, connections, json) { call.application.log.info(it) }
        }

        // --- WebRTC Signaling: /signal/{userId}?token=token_<userId> (path userId must match token) ---
        webSocket("/signal/{userId}") {
            val token = call.request.queryParameters["token"]
            if (token.isNullOrBlank() || !token.startsWith("token_")) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing token"))
                return@webSocket
            }
            val userId = token.removePrefix("token_").trim()
            if (userId.isBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))
                return@webSocket
            }
            val pathUserId = call.parameters["userId"]
            if (pathUserId != null && pathUserId != userId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "userId mismatch"))
                return@webSocket
            }
            handleWsSession(userId, this, connections, json) { call.application.log.info(it) }
        }

        authenticate("auth-jwt") {

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
