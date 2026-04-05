package com.securechat.server

import com.securechat.server.models.AccountBackups
import com.securechat.server.models.AuthUsers
import com.securechat.server.models.Backups
import com.securechat.server.models.ChatParticipants
import com.securechat.server.models.Chats
import com.securechat.server.models.ContactInvites
import com.securechat.server.models.Devices
import com.securechat.server.models.Messages
import com.securechat.server.models.OneTimePreKeys
import com.securechat.server.models.PreKeys
import com.securechat.server.models.Profiles
import com.securechat.server.models.Users
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val dbInitLog = LoggerFactory.getLogger("SecureChatDatabase")

fun initDatabase() {
    val rawUrl = System.getenv("JDBC_DATABASE_URL")
        ?: System.getenv("DATABASE_URL")
        ?: error("DATABASE_URL (or JDBC_DATABASE_URL) is not set")

    // 1) Получаем JDBC URL
    val jdbcUrl = toJdbcUrl(rawUrl)

    // 2) Достаём креды:
    //    - приоритет: DB_USER/DB_PASS
    //    - затем: PGUSER/PGPASSWORD
    //    - затем: user:pass из DATABASE_URL
    val parsedCreds = parseUserPassFromDatabaseUrl(rawUrl)
    val dbUser = System.getenv("DB_USER")
        ?: System.getenv("PGUSER")
        ?: parsedCreds?.first
        ?: error("DB user is not set (DB_USER/PGUSER or user in DATABASE_URL)")

    val dbPass = System.getenv("DB_PASS")
        ?: System.getenv("PGPASSWORD")
        ?: parsedCreds?.second
        ?: error("DB password is not set (DB_PASS/PGPASSWORD or password in DATABASE_URL)")

    // Безопасный лог (без пароля)
    println("DB jdbcUrl=$jdbcUrl")
    println("DB user=$dbUser")

    val cfg = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = dbUser
        this.password = dbPass
        this.maximumPoolSize = (System.getenv("DB_POOL")?.toIntOrNull() ?: 10)
        this.isAutoCommit = false
        this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    }

    val ds = HikariDataSource(cfg)
    Database.connect(ds)

    transaction {
        SchemaUtils.create(
            Users,
            AuthUsers,
            Devices,
            PreKeys,
            OneTimePreKeys,
            Backups,
            AccountBackups,
            Profiles,
            Chats,
            ChatParticipants,
            Messages,
            ContactInvites,
        )
        migrateMessagesEncryptedColumns()
        migrateChatIdColumnLengths()
        migrateInviteTokenColumnLength()
        migrateContactInvitesInviterForeignKey()
    }
    transaction {
        logOrphanContactInvites()
    }
}

/**
 * Enforces referential integrity: contact_invites.inviter_user_id → auth_users(user_id).
 * PostgreSQL PK column is `user_id`, not `id`.
 */
private fun Transaction.migrateContactInvitesInviterForeignKey() {
    try {
        exec(
            """
            DELETE FROM contact_invites ci
            WHERE NOT EXISTS (SELECT 1 FROM auth_users au WHERE au.user_id = ci.inviter_user_id);
            """.trimIndent(),
        )
        exec("ALTER TABLE contact_invites DROP CONSTRAINT IF EXISTS fk_contact_invites_inviter_user;")
        exec(
            """
            ALTER TABLE contact_invites
            ADD CONSTRAINT fk_contact_invites_inviter_user
            FOREIGN KEY (inviter_user_id) REFERENCES auth_users(user_id) ON DELETE CASCADE;
            """.trimIndent(),
        )
    } catch (e: Exception) {
        println("contact_invites FK migration: ${e.message}")
    }
}

private fun logOrphanContactInvites() {
    try {
        val rows = ContactInvites
            .join(AuthUsers, JoinType.LEFT, ContactInvites.inviterUserId, AuthUsers.userId)
            .select { AuthUsers.userId.isNull() }
            .toList()
        dbInitLog.warn(
            "contact_invites orphan rows (no auth_users match): count={}",
            rows.size,
        )
        rows.forEach { row ->
            dbInitLog.warn(
                "  orphan invite_token={} inviter_user_id={}",
                row[ContactInvites.inviteToken],
                row[ContactInvites.inviterUserId],
            )
        }
    } catch (e: Exception) {
        dbInitLog.warn("contact_invites orphan check failed: {}", e.message)
    }
}

private fun Transaction.migrateMessagesEncryptedColumns() {
    try {
        exec("ALTER TABLE messages ADD COLUMN IF NOT EXISTS recipient_id VARCHAR(64);")
        exec(
            "ALTER TABLE messages ADD COLUMN IF NOT EXISTS ephemeral_key_id VARCHAR(128) NOT NULL DEFAULT '';",
        )
    } catch (e: Exception) {
        println("messages column migration (may be no-op): ${e.message}")
    }
}

/**
 * Direct chat ids are generated as "d_" + SHA256 hex, so they exceed 64 chars.
 * Widen all chat_id carriers to avoid insert/update failures.
 */
private fun Transaction.migrateChatIdColumnLengths() {
    try {
        exec("ALTER TABLE chats ALTER COLUMN id TYPE VARCHAR(255);")
        exec("ALTER TABLE chat_participants ALTER COLUMN chat_id TYPE VARCHAR(255);")
        exec("ALTER TABLE messages ALTER COLUMN chat_id TYPE VARCHAR(255);")
    } catch (e: Exception) {
        println("chat_id length migration: ${e.message}")
    }
}

private fun Transaction.migrateInviteTokenColumnLength() {
    try {
        exec("ALTER TABLE contact_invites ALTER COLUMN invite_token TYPE VARCHAR(255);")
    } catch (e: Exception) {
        println("invite_token length migration: ${e.message}")
    }
}

/**
 * Render даёт DATABASE_URL вида:
 *   postgres://user:pass@host:port/db
 * или postgresql://...
 * Преобразуем в jdbc:postgresql://host:port/db
 */
private fun toJdbcUrl(databaseUrl: String): String {
    val trimmed = databaseUrl.trim()
    return if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
        val uri = URI(trimmed)
        val host = uri.host ?: error("DATABASE_URL host is null")
        val port = if (uri.port == -1) 5432 else uri.port
        val db = uri.path?.trimStart('/') ?: error("DATABASE_URL db name is null")
        "jdbc:postgresql://$host:$port/$db"
    } else {
        // Уже JDBC
        trimmed
    }
}

/**
 * Достаёт user/pass из postgres://user:pass@host:port/db
 * Важно: корректно обрабатывает спецсимволы (URL-encoded).
 */
private fun parseUserPassFromDatabaseUrl(databaseUrl: String): Pair<String, String>? {
    val trimmed = databaseUrl.trim()
    if (!(trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://"))) return null

    val uri = URI(trimmed)
    val userInfo = uri.userInfo ?: return null // "user:pass"
    val parts = userInfo.split(":", limit = 2)
    if (parts.size != 2) return null

    val user = urlDecode(parts[0])
    val pass = urlDecode(parts[1])
    return user to pass
}

private fun urlDecode(s: String): String =
    URLDecoder.decode(s, StandardCharsets.UTF_8)
