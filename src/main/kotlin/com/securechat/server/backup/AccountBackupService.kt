package com.securechat.server.backup

import com.securechat.server.models.AccountBackups
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class EncryptedAccountBackup(
    val userId: String,
    val encryptedBackupBlob: String,
    val backupVersion: Int,
    val clientUpdatedAt: Long,
)

class AccountBackupService {
    fun upsertEncryptedBackup(
        userId: String,
        encryptedBackupBlob: String,
        backupVersion: Int,
        clientUpdatedAt: Long,
    ) {
        transaction {
            val existing = AccountBackups
                .select { AccountBackups.userId eq userId }
                .firstOrNull()

            if (existing == null) {
                AccountBackups.insert {
                    it[AccountBackups.userId] = userId
                    it[AccountBackups.encryptedBackupBlob] = encryptedBackupBlob
                    it[AccountBackups.backupVersion] = backupVersion
                    it[AccountBackups.clientUpdatedAt] = clientUpdatedAt
                    it[AccountBackups.createdAt] = Clock.System.now()
                    it[AccountBackups.updatedAt] = Clock.System.now()
                }
            } else {
                AccountBackups.update({ AccountBackups.userId eq userId }) {
                    it[AccountBackups.encryptedBackupBlob] = encryptedBackupBlob
                    it[AccountBackups.backupVersion] = backupVersion
                    it[AccountBackups.clientUpdatedAt] = clientUpdatedAt
                    it[AccountBackups.updatedAt] = Clock.System.now()
                }
            }
        }
    }

    fun getEncryptedBackup(userId: String): EncryptedAccountBackup? = transaction {
        val row = AccountBackups
            .select { AccountBackups.userId eq userId }
            .orderBy(AccountBackups.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: return@transaction null

        EncryptedAccountBackup(
            userId = row[AccountBackups.userId],
            encryptedBackupBlob = row[AccountBackups.encryptedBackupBlob],
            backupVersion = row[AccountBackups.backupVersion],
            clientUpdatedAt = row[AccountBackups.clientUpdatedAt],
        )
    }
}

