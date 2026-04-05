package com.securechat.server.contact

import com.securechat.server.auth.AuthUserRepository
import com.securechat.server.models.AuthUsers
import com.securechat.server.models.ContactInvites
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

private val contactInviteLog = LoggerFactory.getLogger(ContactInviteService::class.java)

data class CreatedContactInvite(
    val userId: String,
    val username: String,
    val expiresAt: Long,
    val inviteToken: String,
)

class ContactInviteService(
    private val inviteLifetimeMillis: Long = 5 * 60 * 1000L,
    private val userRepository: AuthUserRepository = AuthUserRepository(),
) {

    fun createInvite(inviterUserId: String): CreatedContactInvite = transaction {
        val inviterRow = userRepository.findById(inviterUserId)
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

        // Diagnostics: equivalent to SELECT * FROM contact_invites WHERE invite_token = ?
        contactInviteLog.info(
            "[acceptInvite] accepterUserId={} inviteToken={}",
            accepterUserId,
            inviteToken,
        )
        contactInviteLog.info(
            "[acceptInvite] contact_invites row: invite_token={} inviter_user_id={} inviter_username={} expires_at={} used={} accepted_by_user_id={} used_at={}",
            row[ContactInvites.inviteToken],
            row[ContactInvites.inviterUserId],
            row[ContactInvites.inviterUsername],
            row[ContactInvites.expiresAt],
            row[ContactInvites.used],
            row[ContactInvites.acceptedByUserId],
            row[ContactInvites.usedAt],
        )

        val inviterRowAuth = userRepository.findById(inviterUserId)
        val inviterExistsInAuthUsers = inviterRowAuth != null
        contactInviteLog.info(
            "[acceptInvite] AuthUsers.exists(inviterUserId={})={}",
            inviterUserId,
            inviterExistsInAuthUsers,
        )

        if (!inviterExistsInAuthUsers) {
            contactInviteLog.warn(
                "[acceptInvite] invite row present but inviter missing from auth_users: inviterUserId={}",
                inviterUserId,
            )
            throw ContactInviteException(ContactInviteError.INVITER_USER_NOT_FOUND)
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
    /** Invite row references inviter_user_id that no longer exists in auth_users. */
    INVITER_USER_NOT_FOUND,
}

class ContactInviteException(
    val error: ContactInviteError,
) : RuntimeException(error.name)

