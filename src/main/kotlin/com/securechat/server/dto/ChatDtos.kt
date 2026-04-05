package com.securechat.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Chat/message HTTP + WS DTOs: opaque [encrypted_payload] only (no plaintext message body).
 * WebSocket: [WsEnvelope] with string [payload] = JSON of inner event.
 */

/** Invite + POST /chats/direct (camelCase JSON, matches Android invite parser). */
@Serializable
data class ChatSummaryResponse(
    val chatId: String,
    val isDirect: Boolean,
    val createdAt: Long,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
)

/** GET /chats — matches Android [com.securechat.data.remote.dto.ChatDto] (snake_case). */
@Serializable
data class ChatListItemResponse(
    @SerialName("chat_id") val chatId: String,
    @SerialName("peer_user_id") val peerUserId: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("participant_id") val participantId: String,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("last_message_at") val lastMessageAt: Long,
    @SerialName("created_at") val createdAt: Long,
)

/** HTTP message row — matches Android [com.securechat.data.remote.dto.MessageDto]. */
@Serializable
data class MessageResponse(
    @SerialName("message_id") val messageId: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("encrypted_payload") val encryptedPayload: String,
    @SerialName("ephemeral_key_id") val ephemeralKeyId: String,
    val timestamp: Long,
    val status: String,
)

/** POST /chats/{id}/messages — matches Android [com.securechat.data.remote.dto.SendChatMessageRequest]. */
@Serializable
data class SendMessageRequest(
    @SerialName("recipient_id") val recipientId: String? = null,
    @SerialName("encrypted_payload") val encryptedPayload: String,
    @SerialName("ephemeral_key_id") val ephemeralKeyId: String = "",
)

/** POST /chats/{id}/messages response — matches Android [com.securechat.data.remote.dto.SendChatMessageResponse]. */
@Serializable
data class SendChatMessageResponse(
    @SerialName("message_id") val messageId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
)

@Serializable
data class DirectChatRequest(
    val otherUserId: String,
)

// --- WebSocket: single envelope + inner payloads (matches Android WebSocketManager) ---

@Serializable
data class WsEnvelope(
    val type: String,
    val payload: JsonElement? = null,
)

@Serializable
data class WsIncomingMessagePayload(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedPayload: String,
    val ephemeralKeyId: String = "",
    val timestamp: Long,
)

@Serializable
data class WsStatusPayload(
    val chatId: String? = null,
    val messageId: String,
    val status: String,
)

@Serializable
data class WsTypingPayload(
    val chatId: String,
    val userId: String,
    val isTyping: Boolean,
)

@Serializable
data class WsChatUpdatedPayload(
    val chatId: String,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long,
)
