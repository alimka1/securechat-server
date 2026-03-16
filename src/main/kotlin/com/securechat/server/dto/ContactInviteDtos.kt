package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class ContactInviteCreateResponse(
    val userId: String,
    val username: String,
    val expiresAt: Long,
    val inviteToken: String,
)

@Serializable
data class ContactInviteAcceptRequest(
    val inviteToken: String,
)

