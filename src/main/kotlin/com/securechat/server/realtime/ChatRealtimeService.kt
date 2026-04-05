package com.securechat.server.realtime

import com.securechat.server.chat.ChatMessage
import com.securechat.server.dto.CallSignalWsPayload
import com.securechat.server.dto.CallStateUpdatePush
import com.securechat.server.dto.IncomingCallPush
import com.securechat.server.dto.PresenceUpdatePush
import com.securechat.server.dto.TypingPush
import com.securechat.server.dto.WsEnvelope
import com.securechat.server.dto.WsIncomingMessagePayload
import com.securechat.server.dto.WsStatusPayload
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class ChatRealtimeService(
    private val json: Json,
) {

    private val connections =
        ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    private val mutex = Mutex()

    suspend fun registerConnection(
        userId: String,
        session: DefaultWebSocketServerSession,
    ) {
        mutex.withLock {
            val set = connections.getOrPut(userId) { mutableSetOf() }
            set.add(session)
        }
    }

    suspend fun removeConnection(
        userId: String,
        session: DefaultWebSocketServerSession,
    ) {
        mutex.withLock {
            val set = connections[userId] ?: return
            set.remove(session)
            if (set.isEmpty()) {
                connections.remove(userId)
            }
        }
    }

    suspend fun pushNewMessage(
        recipients: Collection<String>,
        message: ChatMessage,
    ) {
        if (recipients.isEmpty()) return
        val inner = WsIncomingMessagePayload(
            messageId = message.messageId,
            conversationId = message.chatId,
            senderId = message.senderId,
            recipientId = message.recipientId,
            encryptedPayload = message.encryptedPayload,
            ephemeralKeyId = message.ephemeralKeyId,
            timestamp = message.createdAt,
        )
        val envelope = WsEnvelope(
            type = "message",
            payload = json.encodeToString(WsIncomingMessagePayload.serializer(), inner),
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushMessageStatus(
        recipients: Collection<String>,
        messageId: String,
        status: String,
    ) {
        if (recipients.isEmpty()) return
        val inner = WsStatusPayload(messageId = messageId, status = status)
        val envelope = WsEnvelope(
            type = "status",
            payload = json.encodeToString(WsStatusPayload.serializer(), inner),
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushPresenceUpdate(
        recipients: Collection<String>,
        update: PresenceUpdatePush,
    ) {
        if (recipients.isEmpty()) return
        val envelope = WsEnvelope(
            type = "presence",
            payload = json.encodeToString(PresenceUpdatePush.serializer(), update),
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushTyping(
        recipients: Collection<String>,
        event: TypingPush,
    ) {
        if (recipients.isEmpty()) return
        val envelope = WsEnvelope(
            type = "typing",
            payload = json.encodeToString(TypingPush.serializer(), event),
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushIncomingCall(
        recipientUserId: String,
        event: IncomingCallPush,
    ) {
        val inner = CallSignalWsPayload(
            callId = event.callId,
            fromUserId = event.fromUserId,
            fromDisplayName = null,
            toUserId = null,
            type = "REQUEST",
            timestamp = event.createdAt,
        )
        val envelope = WsEnvelope(
            type = "call",
            payload = json.encodeToString(CallSignalWsPayload.serializer(), inner),
        )
        broadcast(listOf(recipientUserId), json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushCallStateUpdate(
        recipients: Collection<String>,
        event: CallStateUpdatePush,
    ) {
        if (recipients.isEmpty()) return
        val inner = CallSignalWsPayload(
            callId = event.callId,
            fromUserId = event.actorUserId,
            fromDisplayName = null,
            toUserId = event.targetUserId,
            type = event.state,
            timestamp = event.updatedAt,
        )
        val envelope = WsEnvelope(
            type = "call",
            payload = json.encodeToString(CallSignalWsPayload.serializer(), inner),
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun filterOnlineUsers(
        userIds: Collection<String>,
    ): List<String> = mutex.withLock {
        userIds.filter { userId ->
            val sessions = connections[userId]
            sessions != null && sessions.isNotEmpty()
        }
    }

    private suspend fun broadcast(
        recipients: Collection<String>,
        payload: String,
    ) {
        val targetSessions = mutableListOf<DefaultWebSocketServerSession>()

        mutex.withLock {
            recipients.forEach { userId: String ->
                val sessions: MutableSet<DefaultWebSocketServerSession>? = connections[userId]
                if (sessions != null) {
                    targetSessions.addAll(sessions)
                }
            }
        }

        targetSessions.forEach { session: DefaultWebSocketServerSession ->
            runCatching {
                session.send(Frame.Text(payload))
            }
        }
    }
}
