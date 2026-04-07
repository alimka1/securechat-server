package com.securechat.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

/**
 * Chat/message HTTP + WS DTOs: opaque [encryptedPayload] only (no plaintext message body).
 * WebSocket: [WsEnvelope] with object [payload] for inner event data.
 */

/** Invite + POST /chats/direct (camelCase JSON, matches Android invite parser). */
@Serializable
data class ChatSummaryResponse(
    val chatId: String,
    val isDirect: Boolean,
    val createdAt: Long,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
    val peerAvatarUrl: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long? = null,
)

/** GET /chats — matches Android [com.securechat.data.remote.dto.ChatDto] (snake_case). */
@Serializable
data class ChatListItemResponse(
    @SerialName("chat_id") val chatId: String,
    @SerialName("is_direct") val isDirect: Boolean = true,
    @SerialName("peer_user_id") val peerUserId: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerialName("participant_id") val participantId: String,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("last_message_at") val lastMessageAt: Long,
    @SerialName("created_at") val createdAt: Long,
)

/** HTTP message row — matches Android [com.securechat.data.remote.dto.MessageDto]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageResponse(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_message_id") val clientMessageId: String? = null,
    @SerialName("chat_id") val chatId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("encryptedPayload") @JsonNames("encrypted_payload", "content") val encryptedPayload: String,
    @SerialName("ephemeral_key_id") val ephemeralKeyId: String,
    @SerialName("createdAt") @JsonNames("created_at", "timestamp") val createdAt: Long,
    val status: String,
)

/** POST /chats/{id}/messages — matches Android [com.securechat.data.remote.dto.SendChatMessageRequest]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SendMessageRequest(
    @SerialName("clientMessageId") @JsonNames("client_message_id") val clientMessageId: String? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    @SerialName("encryptedPayload") @JsonNames("encrypted_payload", "content") val encryptedPayload: String,
    @SerialName("ephemeral_key_id") val ephemeralKeyId: String = "",
)

/** POST /chats/{id}/messages response — matches Android [com.securechat.data.remote.dto.SendChatMessageResponse]. */
@Serializable
data class SendChatMessageResponse(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val createdAt: Long,
    val status: String,
    val clientMessageId: String,
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
    val clientMessageId: String? = null,
    val conversationId: String,
    val chatId: String? = null,
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
    val clientMessageId: String? = null,
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
    val peerAvatarUrl: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long,
)
