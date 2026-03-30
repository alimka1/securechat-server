package com.securechat.server.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateInviteResponse(
    val token: String,
    val expiresAt: Long,
)

@Serializable
data class ContactInviteAcceptRequest(
    val inviteToken: String,
)

