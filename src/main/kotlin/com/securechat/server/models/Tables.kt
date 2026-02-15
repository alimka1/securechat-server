package com.securechat.server.models

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Users : Table("users") {
    val userId = varchar("user_id", 64)
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
