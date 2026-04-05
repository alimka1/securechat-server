package com.securechat.server.models

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Users : Table("users") {
    val userId = varchar("user_id", 64)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(userId)
}

object AuthUsers : Table("auth_users") {
    val userId = varchar("user_id", 64).index()
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = text("password_hash")
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(userId)
}

object Devices : Table("devices") {
    val deviceId = varchar("device_id", 64)
    val userId = varchar("user_id", 64).index()
    val deviceKey = text("device_key") // base64 pubkey (future binding)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(deviceId)
}

object PreKeys : Table("prekeys") {
    val id = varchar("id", 64) // UUID string
    val userId = varchar("user_id", 64).index()
    val deviceId = varchar("device_id", 64).index()
    val identityKey = text("identity_key") // base64
    val signedPreKeyId = integer("signed_prekey_id")
    val signedPreKey = text("signed_prekey")      // base64
    val signedPreKeySig = text("signed_prekey_sig") // base64
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object OneTimePreKeys : Table("onetime_prekeys") {
    val id = varchar("id", 64) // UUID string
    val userId = varchar("user_id", 64).index()
    val deviceId = varchar("device_id", 64).index()
    val keyId = integer("key_id")
    val publicKey = text("public_key") // base64
    val used = bool("used").default(false).index()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object Backups : Table("backups") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val filePath = text("file_path") // храним имя файла (не absolute path)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object AccountBackups : Table("account_backups") {
    val userId = varchar("user_id", 64)
    val encryptedBackupBlob = text("encrypted_backup_blob")
    val backupVersion = integer("backup_version")
    val clientUpdatedAt = long("client_updated_at")
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(userId)
}

object Profiles : Table("profiles") {
    val userId = varchar("user_id", 64)
    val displayName = varchar("display_name", 255).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(userId)
}

object Chats : Table("chats") {
    val id = varchar("id", 64)
    val isDirect = bool("is_direct").default(true)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object ChatParticipants : Table("chat_participants") {
    val chatId = varchar("chat_id", 64).index()
    val userId = varchar("user_id", 64).index()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(chatId, userId)
}

object Messages : Table("messages") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64).index()
    val senderId = varchar("sender_id", 64).index()
    /** Opaque ciphertext / base64 payload (never interpreted as plaintext). */
    val content = text("content")
    val recipientId = varchar("recipient_id", 64).nullable()
    val ephemeralKeyId = varchar("ephemeral_key_id", 128).default("")
    val status = varchar("status", 32).default("sent") // sending | sent | delivered | read
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object ContactInvites : Table("contact_invites") {
    val inviteToken = varchar("invite_token", 128)
    val inviterUserId = varchar("inviter_user_id", 64).index()
    val inviterUsername = varchar("inviter_username", 64)
    val expiresAt = long("expires_at")
    val used = bool("used").default(false).index()
    val acceptedByUserId = varchar("accepted_by_user_id", 64).nullable()
    val usedAt = long("used_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(inviteToken)
}
