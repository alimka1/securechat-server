package com.securechat.server.auth

import com.securechat.server.models.AuthUsers
import com.securechat.server.models.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.UUID

data class AuthUser(
    val userId: String,
    val username: String,
)

class AuthService {

    fun register(
        username: String,
        password: String,
    ): AuthUser {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(password.isNotBlank()) { "Password must not be blank" }

        val normalizedUsername = username.trim()

        return transaction {
            val existing = AuthUsers
                .select { AuthUsers.username eq normalizedUsername }
                .firstOrNull()
            if (existing != null) {
                error("Username already exists")
            }

            val userId = UUID.randomUUID().toString()
            val passwordHash = hashPassword(password)

            Users.insertIgnore {
                it[Users.userId] = userId
            }

            AuthUsers.insert {
                it[AuthUsers.userId] = userId
                it[AuthUsers.username] = normalizedUsername
                it[AuthUsers.passwordHash] = passwordHash
            }

            AuthUser(
                userId = userId,
                username = normalizedUsername,
            )
        }
    }

    fun login(
        username: String,
        password: String,
    ): AuthUser {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(password.isNotBlank()) { "Password must not be blank" }

        val normalizedUsername = username.trim()

        return transaction {
            val row = AuthUsers
                .select { AuthUsers.username eq normalizedUsername }
                .firstOrNull()
                ?: error("Invalid username or password")

            val storedHash = row[AuthUsers.passwordHash]
            if (!verifyPassword(password, storedHash)) {
                error("Invalid username or password")
            }

            AuthUser(
                userId = row[AuthUsers.userId],
                username = normalizedUsername,
            )
        }
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean = hashPassword(password) == hash
}

