package com.securechat.server.dto

import kotlinx.serialization.Serializable

/** Inner payload for WebSocket type "presence" (matches Android PresenceUpdateDto). */
@Serializable
data class PresenceUpdatePush(
    val id: String,
    val status: String,
    val lastSeen: Long? = null,
)

/** Legacy typing DTO kept for backward compatibility (not used by canonical flow). */
@Serializable
data class TypingPush(
    val id: String,
    val isTyping: Boolean,
)
