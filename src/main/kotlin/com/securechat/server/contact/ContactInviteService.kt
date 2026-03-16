package com.securechat.server.contact

import com.securechat.server.models.AuthUsers
import com.securechat.server.models.ContactInvites
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

data class CreatedContactInvite(
    val userId: String,
    val username: String,
    val expiresAt: Long,
    val inviteToken: String,
)

class ContactInviteService(
    private val inviteLifetimeMillis: Long = 5 * 60 * 1000L,
) {

    fun createInvite(inviterUserId: String): CreatedContactInvite = transaction {
        val inviterRow = AuthUsers
            .select { AuthUsers.userId eq inviterUserId }
            .firstOrNull()
            ?: throw ContactInviteException(ContactInviteError.USER_NOT_FOUND)

        val inviterUsername = inviterRow[AuthUsers.username]
        val expiresAt = System.currentTimeMillis() + inviteLifetimeMillis
        val inviteToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

        ContactInvites.insert {
            it[ContactInvites.inviteToken] = inviteToken
            it[ContactInvites.inviterUserId] = inviterUserId
            it[ContactInvites.inviterUsername] = inviterUsername
            it[ContactInvites.expiresAt] = expiresAt
            it[ContactInvites.used] = false
        }

        CreatedContactInvite(
            userId = inviterUserId,
            username = inviterUsername,
            expiresAt = expiresAt,
            inviteToken = inviteToken,
        )
    }

    fun acceptInvite(
        accepterUserId: String,
        inviteToken: String,
    ): String = transaction {
        val row = ContactInvites
            .select { ContactInvites.inviteToken eq inviteToken }
            .firstOrNull()
            ?: throw ContactInviteException(ContactInviteError.NOT_FOUND)

        if (row[ContactInvites.used]) {
            throw ContactInviteException(ContactInviteError.ALREADY_USED)
        }

        if (System.currentTimeMillis() > row[ContactInvites.expiresAt]) {
            throw ContactInviteException(ContactInviteError.EXPIRED)
        }

        val inviterUserId = row[ContactInvites.inviterUserId]
        if (inviterUserId == accepterUserId) {
            throw ContactInviteException(ContactInviteError.SELF_ACCEPT_NOT_ALLOWED)
        }

        val updated = ContactInvites.update({
            (ContactInvites.inviteToken eq inviteToken) and (ContactInvites.used eq false)
        }) {
            it[used] = true
            it[acceptedByUserId] = accepterUserId
            it[usedAt] = System.currentTimeMillis()
        }

        if (updated == 0) {
            throw ContactInviteException(ContactInviteError.ALREADY_USED)
        }

        inviterUserId
    }
}

enum class ContactInviteError {
    USER_NOT_FOUND,
    NOT_FOUND,
    EXPIRED,
    ALREADY_USED,
    SELF_ACCEPT_NOT_ALLOWED,
}

class ContactInviteException(
    val error: ContactInviteError,
) : RuntimeException(error.name)

