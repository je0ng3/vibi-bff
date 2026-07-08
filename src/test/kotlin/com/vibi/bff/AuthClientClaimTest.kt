package com.vibi.bff

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.config.AuthConfig
import com.vibi.bff.model.AuthUser
import com.vibi.bff.service.AuthService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JWT client claim 발급 회귀 가드 — 발급 경로가 클라이언트를 결정한다:
 * 모바일 네이티브 교환은 default(mobile), device-code(UXP 패널)는 plugin.
 * separation_jobs.client 태깅 (admin 대시보드 분리 집계) 의 1차 신호.
 */
class AuthClientClaimTest {

    private val secret = "test-secret-test-secret-test-secret-0000"

    private fun service() = AuthService(
        config = AuthConfig(
            googleClientIds = listOf("cid"),
            appleClientIds = emptyList(),
            jwtSecret = secret,
            jwtExpirySeconds = 3600,
        ),
        httpClient = HttpClient(MockEngine { respondBadRequest() }),
        userRepository = mockk(relaxed = true),
    )

    private val user = AuthUser(
        sub = "0f8bd2a1-0000-4000-8000-000000000001",
        email = "a@example.com",
        name = "A",
        picture = null,
        role = "user",
    )

    private fun decodedClientClaim(token: String): String? =
        JWT.require(Algorithm.HMAC256(secret))
            .withIssuer(AuthService.ISSUER)
            .withAudience(AuthService.AUDIENCE)
            .build()
            .verify(token)
            .getClaim("client").asString()

    @Test
    fun `default issuance stamps client=mobile`() {
        val issued = service().issueAccessToken(user)
        assertEquals(AuthService.CLIENT_MOBILE, decodedClientClaim(issued.accessToken))
    }

    @Test
    fun `device-code issuance stamps client=plugin`() {
        val issued = service().issueAccessToken(user, AuthService.CLIENT_PLUGIN)
        assertEquals(AuthService.CLIENT_PLUGIN, decodedClientClaim(issued.accessToken))
    }
}
