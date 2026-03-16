package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class AuthResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

