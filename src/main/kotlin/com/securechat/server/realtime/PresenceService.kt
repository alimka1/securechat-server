package com.securechat.server.realtime

import com.securechat.server.chat.ChatService
import com.securechat.server.dto.PresenceUpdatePush
import com.securechat.server.dto.TypingPush
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresenceService(
    private val chatService: ChatService,
    private val realtime: ChatRealtimeService,
) {

    private val connectionCounts = mutableMapOf<String, Int>()
    private val lastSeen = mutableMapOf<String, Long>()
    private val typingByChat = mutableMapOf<String, MutableSet<String>>()
    private val mutex = Mutex()

    suspend fun onConnected(userId: String) {
        val now = System.currentTimeMillis()
        val becameOnline = mutex.withLock {
            val current = connectionCounts[userId] ?: 0
            connectionCounts[userId] = current + 1
            lastSeen[userId] = now
            current == 0
        }
        if (becameOnline) {
            broadcastPresence(userId, "online", now)
        }
    }

    suspend fun onDisconnected(userId: String) {
        val now = System.currentTimeMillis()
        val becameOffline = mutex.withLock {
            val current = connectionCounts[userId] ?: 0
            val offline = when {
                current <= 1 -> {
                    connectionCounts.remove(userId)
                    true
                }
                else -> {
                    connectionCounts[userId] = current - 1
                    false
                }
            }
            lastSeen[userId] = now
            offline
        }
        if (becameOffline) {
            broadcastPresence(userId, "offline", now)
        }
    }

    suspend fun handleTyping(
        userId: String,
        chatId: String,
        isTyping: Boolean,
    ) {
        if (!chatService.isParticipant(chatId, userId)) {
            throw IllegalAccessException("Not a participant of this chat")
        }

        mutex.withLock {
            val set = typingByChat.getOrPut(chatId) { mutableSetOf() }

            if (isTyping) {
                set.add(userId)
            } else {
                set.remove(userId)

                if (set.isEmpty()) {
                    typingByChat.remove(chatId)
                }
            }

            Unit
        }

        val participants = chatService.listParticipantIds(chatId).filter { it != userId }
        val event = TypingPush(
            chatId = chatId,
            userId = userId,
            isTyping = isTyping,
        )
        realtime.pushTyping(participants, event)
    }

    private suspend fun broadcastPresence(
        userId: String,
        status: String,
        lastSeenAt: Long,
    ) {
        val contacts = chatService.listContactIds(userId)
        if (contacts.isEmpty()) return

        val update = PresenceUpdatePush(
            userId = userId,
            status = status,
            lastSeen = lastSeenAt,
        )
        realtime.pushPresenceUpdate(contacts, update)
    }
}

