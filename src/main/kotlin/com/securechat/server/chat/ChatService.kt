package com.securechat.server.chat

import com.securechat.server.models.AuthUsers
import com.securechat.server.models.ChatParticipants
import com.securechat.server.models.Chats
import com.securechat.server.models.Messages
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

private val chatServiceLog = LoggerFactory.getLogger(ChatService::class.java)

data class ChatSummary(
    val chatId: String,
    val isDirect: Boolean,
    val createdAt: Long,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0L,
)

data class ChatMessage(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedPayload: String,
    val ephemeralKeyId: String,
    val createdAt: Long,
    val status: String,
)

class ChatService {
    private val statusRank = mapOf(
        "sending" to 0,
        "sent" to 1,
        "delivered" to 2,
        "read" to 3,
    )


    fun listChatsForUser(userId: String): List<ChatSummary> = transaction {
        val chatRows = (Chats innerJoin ChatParticipants)
            .select { ChatParticipants.userId eq userId }
            .orderBy(Chats.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .toList()

        chatRows.map { row ->
            val chatId = row[Chats.id]
            val isDirect = row[Chats.isDirect]

            var peerUserId: String? = null
            var peerUsername: String? = null
            if (isDirect) {
                val participants = ChatParticipants
                    .select { ChatParticipants.chatId eq chatId }
                    .map { it[ChatParticipants.userId] }
                if (participants.size == 2) {
                    peerUserId = participants.firstOrNull { it != userId }
                    if (peerUserId != null) {
                        peerUsername = AuthUsers
                            .select { AuthUsers.userId eq peerUserId }
                            .firstOrNull()
                            ?.get(AuthUsers.username)
                    }
                }
            }

            ChatSummary(
                chatId = chatId,
                isDirect = isDirect,
                createdAt = row[Chats.createdAt].toJavaInstant().toEpochMilli(),
                peerUserId = peerUserId,
                peerUsername = peerUsername,
                lastMessagePreview = lastMessagePreviewForChat(chatId),
                lastMessageAt = lastMessageAtForChat(chatId),
            )
        }
    }

    private fun lastMessagePreviewForChat(chatId: String): String? = transaction {
        Messages
            .select { Messages.chatId eq chatId }
            .orderBy(Messages.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(Messages.content)
    }

    private fun lastMessageAtForChat(chatId: String): Long = transaction {
        Messages
            .select { Messages.chatId eq chatId }
            .map { it[Messages.createdAt].toJavaInstant().toEpochMilli() }
            .maxOrNull() ?: 0L
    }

    fun getMessagesForChat(
        chatId: String,
        userId: String,
        limit: Int = 50,
    ): List<ChatMessage> = transaction {
        ensureParticipant(chatId, userId)

        Messages
            .select { Messages.chatId eq chatId }
            .orderBy(Messages.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit)
            .map { it.toChatMessage() }
            .sortedBy { it.createdAt }
    }

    fun sendMessage(
        chatId: String,
        senderId: String,
        recipientId: String?,
        encryptedPayload: String,
        ephemeralKeyId: String,
    ): ChatMessage = transaction {
        ensureParticipant(chatId, senderId)

        val resolvedRecipient = recipientId?.trim().orEmpty().ifBlank {
            val others = ChatParticipants
                .select { ChatParticipants.chatId eq chatId }
                .map { it[ChatParticipants.userId] }
                .filter { it != senderId }
            if (others.size == 1) others.first() else ""
        }

        val id = UUID.randomUUID().toString()
        Messages.insert {
            it[Messages.id] = id
            it[Messages.chatId] = chatId
            it[Messages.senderId] = senderId
            it[Messages.content] = encryptedPayload
            it[Messages.recipientId] = if (resolvedRecipient.isBlank()) null else resolvedRecipient
            it[Messages.ephemeralKeyId] = ephemeralKeyId
            it[Messages.status] = "sent"
        }

        Messages
            .select { Messages.id eq id }
            .first()
            .toChatMessage()
    }

    fun getOrCreateDirectChat(
        userId: String,
        otherUserId: String,
    ): ChatSummary = transaction {
        // ensure other user exists (auth_users — registered accounts only)
        val otherExists = AuthUsers
            .select { AuthUsers.userId eq otherUserId }
            .any()
        if (!otherExists) {
            chatServiceLog.warn(
                "[getOrCreateDirectChat] other user not in auth_users: userId={} otherUserId={}",
                userId,
                otherUserId,
            )
            require(false) { "Other user not found" }
        }

        // find existing direct chat with exactly these two participants
        val existingChatId = findDirectChatId(userId, otherUserId)
        if (existingChatId != null) {
            val chatRow = Chats
                .select { Chats.id eq existingChatId }
                .first()
            return@transaction ChatSummary(
                chatId = chatRow[Chats.id],
                isDirect = chatRow[Chats.isDirect],
                createdAt = chatRow[Chats.createdAt].toJavaInstant().toEpochMilli(),
                peerUserId = otherUserId,
                peerUsername = AuthUsers
                    .select { AuthUsers.userId eq otherUserId }
                    .firstOrNull()
                    ?.get(AuthUsers.username),
                lastMessagePreview = lastMessagePreviewForChat(existingChatId),
                lastMessageAt = lastMessageAtForChat(existingChatId),
            )
        }

        // Deterministic id avoids duplicates for the same user pair.
        val chatId = directChatIdFor(userId, otherUserId)

        Chats.insertIgnore {
            it[id] = chatId
            it[isDirect] = true
        }
        ChatParticipants.insertIgnore {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = userId
        }
        ChatParticipants.insertIgnore {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = otherUserId
        }

        val createdAt = Chats
            .select { Chats.id eq chatId }
            .first()[Chats.createdAt]

        ChatSummary(
            chatId = chatId,
            isDirect = true,
            createdAt = createdAt.toJavaInstant().toEpochMilli(),
            peerUserId = otherUserId,
            peerUsername = AuthUsers
                .select { AuthUsers.userId eq otherUserId }
                .firstOrNull()
                ?.get(AuthUsers.username),
            lastMessagePreview = lastMessagePreviewForChat(chatId),
            lastMessageAt = lastMessageAtForChat(chatId),
        )
    }

    fun listParticipantIds(chatId: String): List<String> = transaction {
        ChatParticipants
            .select { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
    }

    fun isParticipant(chatId: String, userId: String): Boolean = transaction {
        ChatParticipants
            .select { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
            .any()
    }

    fun listContactIds(userId: String): List<String> = transaction {
        val chatIds = ChatParticipants
            .select { ChatParticipants.userId eq userId }
            .map { it[ChatParticipants.chatId] }
            .toSet()

        if (chatIds.isEmpty()) {
            emptyList()
        } else {
            ChatParticipants
                .select { (ChatParticipants.chatId inList chatIds.toList()) and (ChatParticipants.userId neq userId) }
                .map { it[ChatParticipants.userId] }
                .distinct()
        }
    }

    fun markMessageDelivered(
        chatId: String,
        messageId: String,
        userId: String,
    ) {
        transaction {
            ensureParticipant(chatId, userId)
            val row = Messages
                .select { (Messages.id eq messageId) and (Messages.chatId eq chatId) }
                .firstOrNull()
                ?: throw IllegalArgumentException("Message not found")

            val currentStatus = row[Messages.status]
            if (shouldPromote(currentStatus, "delivered")) {
                Messages.update({ (Messages.id eq messageId) and (Messages.chatId eq chatId) }) { updateRow ->
                    updateRow[Messages.status] = "delivered"
                }
            }
        }
    }

    fun markMessageRead(
        chatId: String,
        messageId: String,
        userId: String,
    ) {
        transaction {
            ensureParticipant(chatId, userId)

            val row = Messages
                .select { (Messages.id eq messageId) and (Messages.chatId eq chatId) }
                .firstOrNull()
                ?: throw IllegalArgumentException("Message not found")

            val currentStatus = row[Messages.status]
            if (shouldPromote(currentStatus, "read")) {
                Messages.update({ (Messages.id eq messageId) and (Messages.chatId eq chatId) }) { updateRow ->
                    updateRow[Messages.status] = "read"
                }
            }
        }
    }

    private fun ensureParticipant(chatId: String, userId: String) {
        val isParticipant = ChatParticipants
            .select { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
            .any()
        if (!isParticipant) {
            throw IllegalAccessException("User is not a participant of this chat")
        }
    }

    private fun ResultRow.toChatMessage(): ChatMessage =
        ChatMessage(
            messageId = this[Messages.id],
            chatId = this[Messages.chatId],
            senderId = this[Messages.senderId],
            recipientId = this[Messages.recipientId].orEmpty(),
            encryptedPayload = this[Messages.content],
            ephemeralKeyId = this[Messages.ephemeralKeyId],
            createdAt = this[Messages.createdAt].toJavaInstant().toEpochMilli(),
            status = this[Messages.status],
        )

    private fun findDirectChatId(userId: String, otherUserId: String): String? {
        // Chats that are direct and contain userId
        val chatIdsForUser = ChatParticipants
            .select { ChatParticipants.userId eq userId }
            .map { it[ChatParticipants.chatId] }
            .toSet()

        if (chatIdsForUser.isEmpty()) return null

        // Chats that are direct and contain both users
        val candidateDirectChatIds = Chats
            .select { (Chats.id inList chatIdsForUser.toList()) and (Chats.isDirect eq true) }
            .map { it[Chats.id] }

        candidateDirectChatIds.forEach { chatId ->
            val participants = ChatParticipants
                .select { ChatParticipants.chatId eq chatId }
                .map { it[ChatParticipants.userId] }
            if (participants.size == 2 && participants.contains(userId) && participants.contains(otherUserId)) {
                return chatId
            }
        }

        return null
    }

    private fun shouldPromote(currentStatus: String, newStatus: String): Boolean {
        val current = statusRank[currentStatus] ?: 0
        val next = statusRank[newStatus] ?: 0
        return next > current
    }

    private fun directChatIdFor(userA: String, userB: String): String {
        val ordered = listOf(userA, userB).sorted()
        val raw = "${ordered[0]}:${ordered[1]}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }
        return "d_$hash"
    }
}

