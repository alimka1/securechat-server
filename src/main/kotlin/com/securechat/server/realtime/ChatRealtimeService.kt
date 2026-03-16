package com.securechat.server.realtime

import com.securechat.server.dto.MessageResponse
import com.securechat.server.dto.MessageStatusPush
import com.securechat.server.dto.NewMessagePush
import com.securechat.server.dto.PresenceUpdatePush
import com.securechat.server.dto.TypingPush
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
        message: MessageResponse,
    ) {
        if (recipients.isEmpty()) return
        val payload = json.encodeToString(
            NewMessagePush(
                message = message,
            ),
        )
        broadcast(recipients, payload)
    }

    suspend fun pushMessageStatus(
        recipients: Collection<String>,
        status: MessageStatusPush,
    ) {
        if (recipients.isEmpty()) return
        val payload = json.encodeToString(status)
        broadcast(recipients, payload)
    }

    suspend fun pushPresenceUpdate(
        recipients: Collection<String>,
        update: PresenceUpdatePush,
    ) {
        if (recipients.isEmpty()) return
        val payload = json.encodeToString(update)
        broadcast(recipients, payload)
    }

    suspend fun pushTyping(
        recipients: Collection<String>,
        event: TypingPush,
    ) {
        if (recipients.isEmpty()) return
        val payload = json.encodeToString(event)
        broadcast(recipients, payload)
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