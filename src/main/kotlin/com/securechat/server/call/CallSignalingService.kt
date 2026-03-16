package com.securechat.server.call

import com.securechat.server.chat.ChatService
import com.securechat.server.dto.CallStateUpdatePush
import com.securechat.server.dto.IncomingCallPush
import com.securechat.server.realtime.ChatRealtimeService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory signaling state for active call setup/teardown.
 * This is intentionally isolated and can be replaced with persistent/distributed storage later.
 */
class CallSignalingService(
    private val chatService: ChatService,
    private val realtime: ChatRealtimeService,
) {
    private val activeCalls = ConcurrentHashMap<String, ActiveCall>()
    private val mutex = Mutex()

    suspend fun requestCall(
        callerUserId: String,
        chatId: String,
        calleeUserId: String,
    ) {
        require(callerUserId != calleeUserId) { "Cannot call yourself" }

        val participants = chatService.listParticipantIds(chatId)
        require(participants.contains(callerUserId) && participants.contains(calleeUserId)) {
            "Caller and callee must be chat participants"
        }

        val now = System.currentTimeMillis()
        val call = ActiveCall(
            callId = UUID.randomUUID().toString(),
            chatId = chatId,
            callerUserId = callerUserId,
            calleeUserId = calleeUserId,
            state = CallState.RINGING,
            updatedAt = now,
        )

        mutex.withLock {
            activeCalls[call.callId] = call
        }

        realtime.pushIncomingCall(
            recipientUserId = calleeUserId,
            event = IncomingCallPush(
                callId = call.callId,
                chatId = call.chatId,
                fromUserId = call.callerUserId,
                createdAt = now,
            ),
        )

        broadcastState(call, actorUserId = callerUserId, state = CallState.RINGING)
    }

    suspend fun acceptCall(
        userId: String,
        callId: String,
    ) = transitionCall(userId, callId, expectedActor = Actor.CALLEE, newState = CallState.ACCEPTED)

    suspend fun declineCall(
        userId: String,
        callId: String,
    ) {
        transitionCall(userId, callId, expectedActor = Actor.CALLEE, newState = CallState.DECLINED)
        removeCall(callId)
    }

    suspend fun cancelCall(
        userId: String,
        callId: String,
    ) {
        transitionCall(userId, callId, expectedActor = Actor.CALLER, newState = CallState.CANCELLED)
        removeCall(callId)
    }

    suspend fun hangupCall(
        userId: String,
        callId: String,
    ) {
        val call = getCall(callId)
        require(call.participants().contains(userId)) { "User is not part of this call" }
        updateCallState(callId, CallState.ENDED)
        val updated = getCall(callId)
        broadcastState(updated, actorUserId = userId, state = CallState.ENDED)
        removeCall(callId)
    }

    suspend fun relayIce(
        userId: String,
        callId: String,
        payload: String,
    ) {
        val call = getCall(callId)
        require(call.participants().contains(userId)) { "User is not part of this call" }
        val target = call.participants().first { it != userId }
        realtime.pushCallStateUpdate(
            recipients = listOf(target),
            event = CallStateUpdatePush(
                callId = call.callId,
                chatId = call.chatId,
                state = CallState.ICE.value,
                actorUserId = userId,
                targetUserId = target,
                payload = payload,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun onUserDisconnected(userId: String) {
        val affectedCalls = mutex.withLock {
            activeCalls.values.filter { it.participants().contains(userId) }
        }
        affectedCalls.forEach { call ->
            runCatching {
                updateCallState(call.callId, CallState.ENDED)
                val updated = getCall(call.callId)
                broadcastState(updated, actorUserId = userId, state = CallState.ENDED)
                removeCall(call.callId)
            }
        }
    }

    private suspend fun transitionCall(
        userId: String,
        callId: String,
        expectedActor: Actor,
        newState: CallState,
    ) {
        val call = getCall(callId)
        val actorMatches = when (expectedActor) {
            Actor.CALLER -> call.callerUserId == userId
            Actor.CALLEE -> call.calleeUserId == userId
        }
        require(actorMatches) { "Invalid call action for current user" }

        updateCallState(callId, newState)
        val updated = getCall(callId)
        broadcastState(updated, actorUserId = userId, state = newState)
    }

    private suspend fun getCall(callId: String): ActiveCall =
        mutex.withLock {
            activeCalls[callId] ?: throw IllegalArgumentException("Call not found")
        }

    private suspend fun updateCallState(
        callId: String,
        state: CallState,
    ) {
        val now = System.currentTimeMillis()
        mutex.withLock {
            val current = activeCalls[callId] ?: throw IllegalArgumentException("Call not found")
            activeCalls[callId] = current.copy(state = state, updatedAt = now)
        }
    }

    private suspend fun removeCall(callId: String) {
        mutex.withLock {
            activeCalls.remove(callId)
        }
    }

    private suspend fun broadcastState(
        call: ActiveCall,
        actorUserId: String,
        state: CallState,
    ) {
        realtime.pushCallStateUpdate(
            recipients = call.participants(),
            event = CallStateUpdatePush(
                callId = call.callId,
                chatId = call.chatId,
                state = state.value,
                actorUserId = actorUserId,
                updatedAt = call.updatedAt,
            ),
        )
    }
}

private data class ActiveCall(
    val callId: String,
    val chatId: String,
    val callerUserId: String,
    val calleeUserId: String,
    val state: CallState,
    val updatedAt: Long,
) {
    fun participants(): List<String> = listOf(callerUserId, calleeUserId)
}

private enum class Actor {
    CALLER,
    CALLEE,
}

enum class CallState(val value: String) {
    RINGING("ringing"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    ENDED("ended"),
    ICE("ice"),
}

