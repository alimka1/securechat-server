package com.securechat.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.*

object Security {
    private val jwtSecret = System.getenv("JWT_SECRET") ?: "dev_secret_change_in_production"
    private val alg = Algorithm.HMAC256(jwtSecret)

    const val AUD = "securechat-users"
    const val ISS = "securechat-server"

    fun verifier() = JWT.require(alg).withAudience(AUD).withIssuer(ISS).build()
    fun algorithm() = alg

    fun userId(principal: JWTPrincipal): String =
        principal.payload.getClaim("userId").asString()
}
