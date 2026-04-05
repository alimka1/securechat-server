package com.securechat.server.realtime

import com.securechat.server.call.CallSignalingService
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RealtimeCommandService(
    private val json: Json,
    private val presenceService: PresenceService,
    private val callSignalingService: CallSignalingService,
    private val onMessageStatus: suspend (userId: String, chatId: String, messageId: String, status: String) -> Unit = { _, _, _, _ -> },
) {

    suspend fun handleTextCommand(
        userId: String,
        raw: String,
    ) {
        val obj = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return

        when (obj["type"]?.jsonPrimitive?.content?.trim().orEmpty()) {
            "typing" -> {
                val payload = obj["payload"]?.jsonObject
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                val isTyping = payload?.get("isTyping")?.jsonPrimitive?.booleanOrNull ?: false
                if (chatId.isNotBlank()) {
                    presenceService.handleTyping(userId, chatId, isTyping)
                }
            }
            "typing.start", "typing_started" -> {
                val chatId = obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (chatId.isNotBlank()) {
                    presenceService.handleTyping(userId, chatId, true)
                }
            }
            "typing.stop", "typing_stopped" -> {
                val chatId = obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (chatId.isNotBlank()) {
                    presenceService.handleTyping(userId, chatId, false)
                }
            }
            "presence.ping", "presence_ping" -> {
                // Reserved hook for heartbeat updates.
            }
            "message_status" -> {
                val payload = obj["payload"]?.jsonObject
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                val messageId = payload?.get("messageId")?.jsonPrimitive?.content?.trim().orEmpty()
                val status = payload?.get("status")?.jsonPrimitive?.content?.trim().orEmpty().lowercase()
                if (chatId.isNotBlank() && messageId.isNotBlank() && status.isNotBlank()) {
                    onMessageStatus(userId, chatId, messageId, status)
                }
            }
            "call.request", "call_request" -> {
                val chatId = obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty()
                val calleeUserId = obj["toUserId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (chatId.isNotBlank() && calleeUserId.isNotBlank()) {
                    callSignalingService.requestCall(
                        callerUserId = userId,
                        chatId = chatId,
                        calleeUserId = calleeUserId,
                    )
                }
            }
            "call.accept", "call_accept" -> {
                val callId = obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (callId.isNotBlank()) {
                    callSignalingService.acceptCall(userId, callId)
                }
            }
            "call.decline", "call_decline" -> {
                val callId = obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (callId.isNotBlank()) {
                    callSignalingService.declineCall(userId, callId)
                }
            }
            "call.cancel", "call_cancel" -> {
                val callId = obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (callId.isNotBlank()) {
                    callSignalingService.cancelCall(userId, callId)
                }
            }
            "call.hangup", "call_hangup" -> {
                val callId = obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (callId.isNotBlank()) {
                    callSignalingService.hangupCall(userId, callId)
                }
            }
            "call.ice", "call_ice" -> {
                val callId = obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty()
                val payload = obj["payload"]?.jsonPrimitive?.content ?: ""
                if (callId.isNotBlank() && payload.isNotBlank()) {
                    callSignalingService.relayIce(userId, callId, payload)
                }
            }
        }
    }
}

