package com.securechat.server

import com.securechat.server.models.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun initDatabase() {
    val jdbcUrl = System.getenv("JDBC_DATABASE_URL")
        ?: System.getenv("DATABASE_URL")?.let { toJdbcUrl(it) }
        ?: "jdbc:postgresql://localhost:5432/securechat"

    val dbUser = System.getenv("DB_USER") ?: System.getenv("PGUSER") ?: "postgres"
    val dbPass = System.getenv("DB_PASS") ?: System.getenv("PGPASSWORD") ?: "postgres"

    val cfg = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = dbUser
        this.password = dbPass
        this.maximumPoolSize = (System.getenv("DB_POOL")?.toIntOrNull() ?: 10)
        this.isAutoCommit = false
        this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        this.validate()
    }

    val ds = HikariDataSource(cfg)
    Database.connect(ds)

    transaction {
        SchemaUtils.create(Users, Devices, PreKeys, OneTimePreKeys, Backups)
    }
}

/**
 * Render часто даёт DATABASE_URL вида:
 *   postgres://user:pass@host:port/db
 * Преобразуем в jdbc:postgresql://...
 */
private fun toJdbcUrl(databaseUrl: String): String {
    // postgres://user:pass@host:port/db
    val trimmed = databaseUrl.trim()
    return if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
        val noProto = trimmed.substringAfter("://")
        val userPass = noProto.substringBefore("@")
        val hostDb = noProto.substringAfter("@")
        val hostPort = hostDb.substringBefore("/")
        val db = hostDb.substringAfter("/")
        // user/pass берём отдельно из env, чтобы не светить и не парсить криво
        "jdbc:postgresql://$hostPort/$db"
    } else {
        trimmed
    }
}
