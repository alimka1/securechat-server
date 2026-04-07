package com.securechat.server.realtime

import com.securechat.server.call.CallSignalingService
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                val payload = payloadObject(obj)
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val isTyping = payload?.get("isTyping")?.jsonPrimitive?.booleanOrNull
                    ?: obj["isTyping"]?.jsonPrimitive?.booleanOrNull
                    ?: false
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
                val payload = payloadObject(obj)
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val messageId = payload?.get("messageId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["messageId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val status = payload?.get("status")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["status"]?.jsonPrimitive?.content?.trim().orEmpty() }
                    .lowercase()
                if (chatId.isNotBlank() && messageId.isNotBlank() && status.isNotBlank()) {
                    onMessageStatus(userId, chatId, messageId, status)
                }
            }
            "call_signal", "call" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                val toUserId = payload?.get("toUserId")?.jsonPrimitive?.content?.trim().orEmpty()
                val action = payload?.get("action")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { payload?.get("type")?.jsonPrimitive?.content?.trim().orEmpty() }
                    .lowercase()
                val normalizedAction = when (action) {
                    "call_initiation" -> "call_initiation"
                    "ringing" -> "ringing"
                    "accept", "accepted" -> "accept"
                    "reject", "decline", "declined", "cancel", "cancelled" -> "reject"
                    "end", "ended", "hangup" -> "end"
                    "offer" -> "offer"
                    "answer" -> "answer"
                    "ice", "ice_candidate" -> "ice_candidate"
                    else -> action
                }
                val callType = payload?.get("callType")?.jsonPrimitive?.content?.trim().orEmpty()
                val sdp = payload?.get("sdp")?.jsonPrimitive?.content?.trim().orEmpty()
                val candidate = payload?.get("candidate")?.jsonPrimitive?.content?.trim().orEmpty()
                val sdpMid = payload?.get("sdpMid")?.jsonPrimitive?.content
                val sdpMLineIndex = payload?.get("sdpMLineIndex")?.jsonPrimitive?.content?.toIntOrNull()

                when (normalizedAction) {
                    "call_initiation", "request", "ringing_request" -> {
                        if (chatId.isNotBlank() && toUserId.isNotBlank()) {
                            callSignalingService.requestCall(
                                callerUserId = userId,
                                chatId = chatId,
                                calleeUserId = toUserId,
                                callType = callType,
                                requestedCallId = callId
                            )
                        }
                    }
                    "ringing" -> if (callId.isNotBlank()) {
                        callSignalingService.markRinging(userId, callId)
                    }
                    "accept", "accepted" -> if (callId.isNotBlank()) {
                        callSignalingService.acceptCall(userId, callId)
                    }
                    "reject", "decline", "declined", "cancel", "cancelled" -> if (callId.isNotBlank()) {
                        // Caller cancel maps to cancelCall; callee reject maps to declineCall.
                        runCatching { callSignalingService.declineCall(userId, callId) }
                            .recoverCatching { callSignalingService.cancelCall(userId, callId) }
                    }
                    "end", "ended", "hangup" -> if (callId.isNotBlank()) {
                        callSignalingService.hangupCall(userId, callId)
                    }
                    "offer" -> if (callId.isNotBlank() && sdp.isNotBlank()) {
                        callSignalingService.relayOffer(userId, callId, sdp)
                    }
                    "answer" -> if (callId.isNotBlank() && sdp.isNotBlank()) {
                        callSignalingService.relayAnswer(userId, callId, sdp)
                    }
                    "ice", "ice_candidate" -> if (callId.isNotBlank() && candidate.isNotBlank()) {
                        callSignalingService.relayIce(
                            userId = userId,
                            callId = callId,
                            candidate = candidate,
                            sdpMid = sdpMid,
                            sdpMLineIndex = sdpMLineIndex
                        )
                    }
                }
            }
            "call.request", "call_request" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val chatId = payload?.get("chatId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["chatId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val calleeUserId = payload?.get("toUserId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["toUserId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val callType = payload?.get("callType")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callType"]?.jsonPrimitive?.content?.trim().orEmpty() }
                if (chatId.isNotBlank() && calleeUserId.isNotBlank()) {
                    callSignalingService.requestCall(
                        callerUserId = userId,
                        chatId = chatId,
                        calleeUserId = calleeUserId,
                        callType = callType,
                        requestedCallId = callId,
                    )
                }
            }
            "call.accept", "call_accept" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                if (callId.isNotBlank()) {
                    callSignalingService.acceptCall(userId, callId)
                }
            }
            "call.decline", "call_decline" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                if (callId.isNotBlank()) {
                    callSignalingService.declineCall(userId, callId)
                }
            }
            "call.cancel", "call_cancel" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                if (callId.isNotBlank()) {
                    callSignalingService.cancelCall(userId, callId)
                }
            }
            "call.hangup", "call_hangup" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                if (callId.isNotBlank()) {
                    callSignalingService.hangupCall(userId, callId)
                }
            }
            "call.ice", "call_ice" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val candidate = payload?.get("candidate")?.jsonPrimitive?.content
                    ?: obj["candidate"]?.jsonPrimitive?.content
                val sdpMid = payload?.get("sdpMid")?.jsonPrimitive?.content
                    ?: obj["sdpMid"]?.jsonPrimitive?.content
                val sdpMLineIndex = payload?.get("sdpMLineIndex")?.jsonPrimitive?.content?.toIntOrNull()
                    ?: obj["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull()
                if (callId.isNotBlank() && !candidate.isNullOrBlank()) {
                    callSignalingService.relayIce(
                        userId = userId,
                        callId = callId,
                        candidate = candidate,
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex
                    )
                }
            }
            "call.offer", "call_offer" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val sdp = payload?.get("sdp")?.jsonPrimitive?.content
                    ?: obj["sdp"]?.jsonPrimitive?.content
                if (callId.isNotBlank() && !sdp.isNullOrBlank()) {
                    callSignalingService.relayOffer(userId = userId, callId = callId, sdp = sdp)
                }
            }
            "call.answer", "call_answer" -> {
                val payload = payloadObject(obj)
                val callId = payload?.get("callId")?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { obj["callId"]?.jsonPrimitive?.content?.trim().orEmpty() }
                val sdp = payload?.get("sdp")?.jsonPrimitive?.content
                    ?: obj["sdp"]?.jsonPrimitive?.content
                if (callId.isNotBlank() && !sdp.isNullOrBlank()) {
                    callSignalingService.relayAnswer(userId = userId, callId = callId, sdp = sdp)
                }
            }
        }
    }

    private fun payloadObject(root: JsonObject): JsonObject? {
        val payload = root["payload"] ?: return null
        return when (payload) {
            is JsonObject -> payload
            is JsonPrimitive ->
                if (payload.isString) {
                    runCatching { json.parseToJsonElement(payload.content).jsonObject }.getOrNull()
                } else {
                    null
                }
            else -> null
        }
    }
}

