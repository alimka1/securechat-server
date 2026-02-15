package com.securechat.server.models

import kotlinx.serialization.Serializable

@Serializable
data class SignalMessage(
    val to: String?,        // userId получателя
    val from: String?,      // userId отправителя (можно не доверять, сервер сам добавит позже)
    val type: String,       // "offer" | "answer" | "ice" | "ping" | etc.
    val payload: String     // JSON/base64 blob (сервер не парсит)
)
