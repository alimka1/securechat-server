package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class BackupUploadRequest(
    val userId: String,
    val encryptedBackupBlob: String,
    val backupVersion: Int,
    val clientUpdatedAt: Long,
)

@Serializable
data class BackupUploadResponse(
    val status: String,
)

@Serializable
data class BackupRestoreResponse(
    val userId: String,
    val encryptedBackupBlob: String,
    val backupVersion: Int,
    val clientUpdatedAt: Long,
)

