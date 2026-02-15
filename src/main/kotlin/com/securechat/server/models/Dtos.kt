package com.securechat.server.models

import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val userId: String)

@Serializable
data class AuthResponse(val token: String)

@Serializable
data class PublishPreKeyRequest(
    val userId: String,
    val deviceId: String,
    val identityKey: String,       // base64
    val deviceKey: String,         // base64 (auth/device binding future)
    val signedPreKeyId: Int,
    val signedPreKey: String,      // base64
    val signedPreKeySig: String,   // base64
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList()
)

@Serializable
data class OneTimePreKeyDto(
    val keyId: Int,
    val publicKey: String          // base64
)

@Serializable
data class PreKeyBundleResponse(
    val userId: String,
    val deviceId: String,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySig: String,
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKey: String? = null
)

@Serializable
data class ErrorResponse(val error: String)
