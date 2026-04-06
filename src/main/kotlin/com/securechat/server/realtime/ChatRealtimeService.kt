package com.securechat.server.realtime

import com.securechat.server.chat.ChatMessage
import com.securechat.server.dto.CallSignalWsPayload
import com.securechat.server.dto.CallStateUpdatePush
import com.securechat.server.dto.IncomingCallPush
import com.securechat.server.dto.PresenceUpdatePush
import com.securechat.server.dto.WsEnvelope
import com.securechat.server.dto.WsChatUpdatedPayload
import com.securechat.server.dto.WsIncomingMessagePayload
import com.securechat.server.dto.WsStatusPayload
import com.securechat.server.dto.WsTypingPayload
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            clientMessageId = message.clientMessageId,
            conversationId = message.chatId,
            chatId = message.chatId,
            senderId = message.senderId,
            recipientId = message.recipientId,
            encryptedPayload = message.encryptedPayload,
            ephemeralKeyId = message.ephemeralKeyId,
            timestamp = message.createdAt,
        )
        val envelope = WsEnvelope(
            type = "new_message",
            payload = buildJsonObject {
                put("messageId", inner.messageId)
                inner.clientMessageId?.let { put("clientMessageId", it) }
                put("conversationId", inner.conversationId)
                put("chatId", inner.chatId ?: inner.conversationId)
                put("senderId", inner.senderId)
                put("recipientId", inner.recipientId)
                put("encryptedPayload", inner.encryptedPayload)
                put("ephemeralKeyId", inner.ephemeralKeyId)
                put("timestamp", inner.timestamp)
            },
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushMessageStatus(
        recipients: Collection<String>,
        chatId: String,
        messageId: String,
        clientMessageId: String? = null,
        status: String,
    ) {
        if (recipients.isEmpty()) return
        val inner = WsStatusPayload(
            chatId = chatId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            status = status,
        )
        val envelope = WsEnvelope(
            type = "message_status",
            payload = buildJsonObject {
                put("chatId", inner.chatId)
                put("conversationId", inner.chatId)
                put("messageId", inner.messageId)
                inner.clientMessageId?.let { put("clientMessageId", it) }
                put("status", inner.status)
            },
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
            payload = buildJsonObject {
                put("id", update.id)
                put("userId", update.id)
                put("status", update.status)
                update.lastSeen?.let { put("lastSeen", it) }
            },
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushTyping(
        recipients: Collection<String>,
        event: WsTypingPayload,
    ) {
        if (recipients.isEmpty()) return
        val envelope = WsEnvelope(
            type = "typing",
            payload = buildJsonObject {
                put("chatId", event.chatId)
                put("userId", event.userId)
                put("id", event.userId)
                put("isTyping", event.isTyping)
            },
        )
        broadcast(recipients, json.encodeToString(WsEnvelope.serializer(), envelope))
    }

    suspend fun pushChatUpdated(
        recipients: Collection<String>,
        event: WsChatUpdatedPayload,
    ) {
        if (recipients.isEmpty()) return
        val envelope = WsEnvelope(
            type = "chat_updated",
            payload = buildJsonObject {
                put("chatId", event.chatId)
                event.peerUserId?.let { put("peerUserId", it) }
                event.peerUsername?.let { put("peerUsername", it) }
                event.peerUserId?.let { put("participantId", it) }
                event.lastMessagePreview?.let { put("lastMessagePreview", it) }
                put("lastMessageAt", event.lastMessageAt)
            },
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
            payload = buildJsonObject {
                put("callId", inner.callId)
                put("fromUserId", inner.fromUserId)
                inner.fromDisplayName?.let { put("fromDisplayName", it) }
                inner.toUserId?.let { put("toUserId", it) }
                put("type", inner.type)
                put("timestamp", inner.timestamp)
            },
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
            payload = buildJsonObject {
                put("callId", inner.callId)
                put("fromUserId", inner.fromUserId)
                inner.fromDisplayName?.let { put("fromDisplayName", it) }
                inner.toUserId?.let { put("toUserId", it) }
                put("type", inner.type)
                put("timestamp", inner.timestamp)
            },
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
        val targetSessions = mutableListOf<Pair<String, DefaultWebSocketServerSession>>()

        mutex.withLock {
            recipients.forEach { userId: String ->
                val sessions: MutableSet<DefaultWebSocketServerSession>? = connections[userId]
                if (sessions != null) {
                    targetSessions.addAll(sessions.map { userId to it })
                }
            }
        }

        val failed = mutableListOf<Pair<String, DefaultWebSocketServerSession>>()
        targetSessions.forEach { (userId, session) ->
            val ok = runCatching {
                session.send(Frame.Text(payload))
            }.isSuccess
            if (!ok) {
                failed += userId to session
            }
        }

        if (failed.isEmpty()) return

        mutex.withLock {
            failed.forEach { (userId, session) ->
                val sessions = connections[userId] ?: return@forEach
                sessions.remove(session)
                if (sessions.isEmpty()) {
                    connections.remove(userId)
                }
            }
        }
    }
}
