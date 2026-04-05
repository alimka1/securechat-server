package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class IncomingCallPush(
    val type: String = "incoming_call",
    val callId: String,
    val chatId: String,
    val fromUserId: String,
    val createdAt: Long,
)

@Serializable
data class CallStateUpdatePush(
    val type: String = "call_state_update",
    val callId: String,
    val chatId: String,
    val state: String,
    val actorUserId: String,
    val targetUserId: String? = null,
    val payload: String? = null, // optional signaling payload (e.g. ICE candidate blob)
    val updatedAt: Long,
)

/** WebSocket inner payload for type "call" / call_signal (matches Android CallSignalDto). */
@Serializable
data class CallSignalWsPayload(
    val callId: String,
    val fromUserId: String,
    val fromDisplayName: String? = null,
    val toUserId: String? = null,
    val type: String,
    val timestamp: Long,
)

