package com.securechat.server.routes

import com.securechat.server.Security
import com.securechat.server.chat.ChatService
import com.securechat.server.dto.ChatSummaryResponse
import com.securechat.server.dto.DirectChatRequest
import com.securechat.server.dto.MessageResponse
import com.securechat.server.dto.MessageStatusPush
import com.securechat.server.dto.SendMessageRequest
import com.securechat.server.models.ErrorResponse
import com.securechat.server.realtime.ChatRealtimeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.chatRoutes(
    chatService: ChatService,
    realtime: ChatRealtimeService,
) {

    route("/chats") {

        get {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val chats = chatService.listChatsForUser(userId)
            val response = chats.map {
                ChatSummaryResponse(
                    chatId = it.chatId,
                    isDirect = it.isDirect,
                    createdAt = it.createdAt,
                )
            }
            call.respond(response)
        }

        get("/{chatId}/messages") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val chatId = call.parameters["chatId"]?.trim().orEmpty()
            if (chatId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("chatId is required"),
                )
                return@get
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

            try {
                val messages = chatService.getMessagesForChat(chatId, userId, limit)
                val response = messages.map {
                    MessageResponse(
                        messageId = it.messageId,
                        chatId = it.chatId,
                        senderId = it.senderId,
                        content = it.content,
                        createdAt = it.createdAt,
                        status = it.status,
                    )
                }
                call.respond(response)
            } catch (e: IllegalAccessException) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Not a participant of this chat"),
                )
            }
        }

        post("/{chatId}/messages") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val chatId = call.parameters["chatId"]?.trim().orEmpty()
            if (chatId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("chatId is required"),
                )
                return@post
            }

            val body = try {
                call.receive<SendMessageRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { content }"),
                )
                return@post
            }

            val content = body.content.trim()
            if (content.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("content must not be blank"),
                )
                return@post
            }

            try {
                val msg = chatService.sendMessage(chatId, userId, content)
                val response = MessageResponse(
                    messageId = msg.messageId,
                    chatId = msg.chatId,
                    senderId = msg.senderId,
                    content = msg.content,
                    createdAt = msg.createdAt,
                    status = msg.status,
                )
                val participants = chatService.listParticipantIds(chatId)
                realtime.pushNewMessage(participants, response)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: IllegalAccessException) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Not a participant of this chat"),
                )
            }
        }

        post("/{chatId}/messages/{messageId}/read") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val chatId = call.parameters["chatId"]?.trim().orEmpty()
            val messageId = call.parameters["messageId"]?.trim().orEmpty()

            if (chatId.isBlank() || messageId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("chatId and messageId are required"),
                )
                return@post
            }

            try {
                chatService.markMessageRead(chatId, messageId, userId)
                val participants = chatService.listParticipantIds(chatId)
                val statusEvent = MessageStatusPush(
                    messageId = messageId,
                    chatId = chatId,
                    status = "read",
                )
                realtime.pushMessageStatus(participants, statusEvent)
                call.respond(HttpStatusCode.NoContent, Unit)
            } catch (e: IllegalAccessException) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Not a participant of this chat"),
                )
            }
        }

        post("/direct") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            val body = try {
                call.receive<DirectChatRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { otherUserId }"),
                )
                return@post
            }

            val otherUserId = body.otherUserId.trim()
            if (otherUserId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("otherUserId must not be blank"),
                )
                return@post
            }

            if (otherUserId == userId) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Cannot create direct chat with yourself"),
                )
                return@post
            }

            try {
                val chat = chatService.getOrCreateDirectChat(userId, otherUserId)
                val response = ChatSummaryResponse(
                    chatId = chat.chatId,
                    isDirect = chat.isDirect,
                    createdAt = chat.createdAt,
                )
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(e.message ?: "Other user not found"),
                )
            }
        }
    }
}
