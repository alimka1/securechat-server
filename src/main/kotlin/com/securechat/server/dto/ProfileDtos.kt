package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserProfileResponse(
    val userId: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val createdAt: Long,
)

@Serializable
data class ProfileUpdateRequest(
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

