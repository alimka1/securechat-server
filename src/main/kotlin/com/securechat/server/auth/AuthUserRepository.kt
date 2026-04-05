package com.securechat.server.auth

import com.securechat.server.models.AuthUsers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Lookup for rows in [AuthUsers] (table `auth_users`, PK column `user_id`).
 */
class AuthUserRepository {
    fun findById(userId: String): ResultRow? = transaction {
        AuthUsers.select { AuthUsers.userId eq userId }.firstOrNull()
    }
}
