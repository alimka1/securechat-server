package com.securechat.server.routes

import com.securechat.server.Security
import com.securechat.server.chat.ChatService
import com.securechat.server.contact.ContactInviteError
import com.securechat.server.contact.ContactInviteException
import com.securechat.server.contact.ContactInviteService
import com.securechat.server.dto.ChatSummaryResponse
import com.securechat.server.dto.ContactInviteAcceptRequest
import com.securechat.server.dto.CreateInviteResponse
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

fun Route.contactInviteRoutes(
    contactInviteService: ContactInviteService,
    chatService: ChatService,
) {
    route("/contacts/invite") {

        post("/create") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = Security.userId(principal)

            try {
                val invite = contactInviteService.createInvite(userId)
                call.respond(
                    CreateInviteResponse(
                        token = invite.inviteToken,
                        expiresAt = invite.expiresAt,
                    ),
                )
            } catch (e: ContactInviteException) {
                when (e.error) {
                    ContactInviteError.USER_NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    }
                    else -> {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Invite creation failed"))
                    }
                }
            }
        }

        post("/accept") {
            val principal = call.principal<JWTPrincipal>()!!
            val accepterUserId = Security.userId(principal)

            val body = try {
                call.receive<ContactInviteAcceptRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body. Expected JSON: { inviteToken }"),
                )
                return@post
            }

            val inviteToken = body.inviteToken.trim()
            if (inviteToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("inviteToken must not be blank"))
                return@post
            }

            try {
                val inviterUserId = contactInviteService.acceptInvite(accepterUserId, inviteToken)
                val chat = chatService.getOrCreateDirectChat(accepterUserId, inviterUserId)
                call.respond(
                    ChatSummaryResponse(
                        chatId = chat.chatId,
                        isDirect = chat.isDirect,
                        createdAt = chat.createdAt,
                    ),
                )
            } catch (e: ContactInviteException) {
                when (e.error) {
                    ContactInviteError.NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Invite token not found"))
                    }
                    ContactInviteError.EXPIRED -> {
                        call.respond(HttpStatusCode.Gone, ErrorResponse("Invite token expired"))
                    }
                    ContactInviteError.ALREADY_USED -> {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("Invite token already used"))
                    }
                    ContactInviteError.SELF_ACCEPT_NOT_ALLOWED -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Cannot accept your own invite"))
                    }
                    ContactInviteError.USER_NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    }
                    ContactInviteError.INVITER_USER_NOT_FOUND -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Invite owner user not found in database"),
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Other user not found"))
            }
        }
    }
}

