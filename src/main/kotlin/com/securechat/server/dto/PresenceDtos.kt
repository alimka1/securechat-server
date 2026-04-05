package com.securechat.server.dto

import kotlinx.serialization.Serializable

/** Inner payload for WebSocket type "presence" (matches Android PresenceUpdateDto). */
@Serializable
data class PresenceUpdatePush(
    val id: String,
    val status: String,
    val lastSeen: Long? = null,
)

/** Inner payload for WebSocket type "typing" (matches Android TypingUpdateDto). */
@Serializable
data class TypingPush(
    val id: String,
    val isTyping: Boolean,
)
