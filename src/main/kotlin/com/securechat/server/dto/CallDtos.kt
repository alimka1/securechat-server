package com.securechat.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IncomingCallPush(
    val type: String = "incoming_call",
    val callId: String,
    val chatId: String,
    val fromUserId: String,
    val callType: String = "audio",
    val transportMode: String = "DIRECT_P2P",
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
    val callType: String? = null,
    val transportMode: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val updatedAt: Long,
)

/** WebSocket inner payload for type "call" / call_signal (matches Android CallSignalDto). */
@Serializable
data class CallSignalWsPayload(
    val callId: String,
    val chatId: String? = null,
    val fromUserId: String,
    val fromDisplayName: String? = null,
    val toUserId: String? = null,
    @SerialName("type")
    val type: String, // compatibility alias for older clients
    val action: String = type.lowercase(),
    val callType: String? = null,
    val transportMode: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val timestamp: Long,
)

