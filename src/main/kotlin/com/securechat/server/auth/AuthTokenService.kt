package com.securechat.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.interfaces.DecodedJWT
import com.securechat.server.Security
import java.util.Date

data class AuthTokens(
    val userId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

class AuthTokenService(
    private val accessTtlSeconds: Long = 15 * 60,           // 15 minutes
    private val refreshTtlSeconds: Long = 30L * 24 * 3600,  // 30 days
) {

    private val refreshAudience = "securechat-refresh"

    private val refreshVerifier: JWTVerifier = JWT
        .require(Security.algorithm())
        .withAudience(refreshAudience)
        .withIssuer(Security.ISS)
        .build()

    fun generateTokens(
        userId: String,
        username: String,
    ): AuthTokens {
        val now = System.currentTimeMillis()
        val accessExpiresAt = Date(now + accessTtlSeconds * 1000)
        val refreshExpiresAt = Date(now + refreshTtlSeconds * 1000)

        val accessToken = JWT.create()
            .withAudience(Security.AUD)
            .withIssuer(Security.ISS)
            .withSubject(userId)
            .withClaim("userId", userId)
            .withClaim("username", username)
            .withClaim("type", "access")
            .withExpiresAt(accessExpiresAt)
            .sign(Security.algorithm())

        val refreshToken = JWT.create()
            .withAudience(refreshAudience)
            .withIssuer(Security.ISS)
            .withSubject(userId)
            .withClaim("userId", userId)
            .withClaim("username", username)
            .withClaim("type", "refresh")
            .withExpiresAt(refreshExpiresAt)
            .sign(Security.algorithm())

        return AuthTokens(
            userId = userId,
            username = username,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = accessExpiresAt.time,
        )
    }

    fun refreshTokens(refreshToken: String): AuthTokens {
        val decoded = verifyRefreshToken(refreshToken)
        val userId = decoded.getClaim("userId").asString()
        val username = decoded.getClaim("username").asString()

        require(!userId.isNullOrBlank()) { "Refresh token missing userId" }
        require(!username.isNullOrBlank()) { "Refresh token missing username" }

        return generateTokens(
            userId = userId,
            username = username,
        )
    }

    private fun verifyRefreshToken(token: String): DecodedJWT {
        val decoded = refreshVerifier.verify(token)
        val type = decoded.getClaim("type").asString()
        require(type == "refresh") { "Invalid token type" }
        return decoded
    }
}

