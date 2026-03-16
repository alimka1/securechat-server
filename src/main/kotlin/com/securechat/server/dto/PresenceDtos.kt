package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class PresenceUpdatePush(
    val type: String = "presence",
    val userId: String,
    val status: String, // "online" | "offline"
    val lastSeen: Long,
)

@Serializable
data class TypingPush(
    val type: String = "typing",
    val chatId: String,
    val userId: String,
    val isTyping: Boolean,
)

