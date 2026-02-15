package com.securechat.server

import com.securechat.server.models.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
        SchemaUtils.create(Users, Devices, PreKeys, OneTimePreKeys, Backups)
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
