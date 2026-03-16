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
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.util.UUID

data class ChatSummary(
    val chatId: String,
    val isDirect: Boolean,
    val createdAt: Long,
)

data class ChatMessage(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val createdAt: Long,
    val status: String,
)

class ChatService {

    fun listChatsForUser(userId: String): List<ChatSummary> = transaction {
        (Chats innerJoin ChatParticipants)
            .select { ChatParticipants.userId eq userId }
            .orderBy(Chats.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { row ->
                ChatSummary(
                    chatId = row[Chats.id],
                    isDirect = row[Chats.isDirect],
                    createdAt = row[Chats.createdAt].toJavaInstant().toEpochMilli(),
                )
            }
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
        content: String,
    ): ChatMessage = transaction {
        ensureParticipant(chatId, senderId)

        val id = UUID.randomUUID().toString()
        Messages.insert {
            it[Messages.id] = id
            it[Messages.chatId] = chatId
            it[Messages.senderId] = senderId
            it[Messages.content] = content
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
        // ensure other user exists
        val otherExists = AuthUsers
            .select { AuthUsers.userId eq otherUserId }
            .any()
        require(otherExists) { "Other user not found" }

        // try to find existing direct chat with exactly these two participants
        val existingChatId = findDirectChatId(userId, otherUserId)
        if (existingChatId != null) {
            val chatRow = Chats
                .select { Chats.id eq existingChatId }
                .first()
            return@transaction ChatSummary(
                chatId = chatRow[Chats.id],
                isDirect = chatRow[Chats.isDirect],
                createdAt = chatRow[Chats.createdAt].toJavaInstant().toEpochMilli(),
            )
        }

        val chatId = UUID.randomUUID().toString()

        Chats.insert {
            it[id] = chatId
            it[isDirect] = true
        }
        ChatParticipants.insert {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = userId
        }
        ChatParticipants.insert {
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
        )
    }

    fun listParticipantIds(chatId: String): List<String> = transaction {
        ChatParticipants
            .select { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
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

    fun markMessageRead(
        chatId: String,
        messageId: String,
        userId: String,
    ) {
        transaction {
            ensureParticipant(chatId, userId)

            Messages.update({ (Messages.id eq messageId) and (Messages.chatId eq chatId) }) { row ->
                row[Messages.status] = "read"
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
            content = this[Messages.content],
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

        // Chats that also contain otherUserId
        val chatIdsForOther = ChatParticipants
            .select { (ChatParticipants.userId eq otherUserId) and (ChatParticipants.chatId inList chatIdsForUser.toList()) }
            .map { it[ChatParticipants.chatId] }
            .toSet()

        if (chatIdsForOther.isEmpty()) return null

        val directChat = Chats
            .select { (Chats.id inList chatIdsForOther.toList()) and (Chats.isDirect eq true) }
            .limit(1)
            .firstOrNull()

        return directChat?.get(Chats.id)
    }
}

