package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatSummaryResponse(
    val chatId: String,
    val isDirect: Boolean,
    val createdAt: Long,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
)

@Serializable
data class MessageResponse(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val createdAt: Long,
    val status: String,
)

@Serializable
data class SendMessageRequest(
    val content: String,
)

@Serializable
data class DirectChatRequest(
    val otherUserId: String,
)

@Serializable
data class NewMessagePush(
    val type: String = "new_message",
    val message: MessageResponse,
)

@Serializable
data class MessageStatusPush(
    val type: String = "message_status",
    val messageId: String,
    val chatId: String,
    val status: String,
    val updatedAt: Long,
)

