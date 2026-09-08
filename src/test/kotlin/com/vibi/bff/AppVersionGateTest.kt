package com.vibi.bff

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.plugins.configureAppVersionGate
import com.vibi.bff.plugins.configureSerialization
import com.vibi.bff.service.AuthService
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 최소 지원 앱 버전 게이트 회귀 가드. 잘못 막으면 UXP 패널·admin·서버 콜백까지 죽으므로,
 * "누구를 막고 누구를 통과시키는지" 를 경로/헤더/claim 조합으로 고정한다.
 */
class AppVersionGateTest {

    private val secret = "test-secret-test-secret-test-secret-0000"

    private fun token(client: String): String = JWT.create()
        .withIssuer(AuthService.ISSUER)
        .withAudience(AuthService.AUDIENCE)
        .withSubject(UUID.randomUUID().toString())
        .withClaim("client", client)
        .sign(Algorithm.HMAC256(secret))

    private suspend fun ApplicationTestBuilder.hit(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        version: String? = null,
        bearer: String? = null,
    ): HttpResponse = client.request(path) {
        this.method = method
        version?.let { header("X-App-Version", it) }
        bearer?.let { header("Authorization", "Bearer $it") }
    }

    private fun gated(minVersion: String?, block: suspend ApplicationTestBuilder.() -> Unit) {
        if (minVersion == null) System.clearProperty("MIN_APP_VERSION")
        else System.setProperty("MIN_APP_VERSION", minVersion)
        try {
            testApplication {
                application {
                    configureSerialization()
                    configureAppVersionGate(secret)
                    routing {
                        get("/healthz") { call.respondText("ok") }
                        get("/api/v2/credits") { call.respondText("ok") }
                        get("/api/v2/admin/analytics") { call.respondText("ok") }
                        get("/api/v2/credits/admob-ssv") { call.respondText("ok") }
                        post("/api/v2/auth/google") { call.respondText("ok") }
                    }
                }
                block()
            }
        } finally {
            System.clearProperty("MIN_APP_VERSION")
        }
    }

    @Test
    fun `outdated version header is blocked`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.UpgradeRequired, hit("/api/v2/credits", version = "1.0.2").status)
    }

    @Test
    fun `minimum and newer versions pass`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", version = "1.1.0").status)
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", version = "1.2").status)
    }

    @Test
    fun `unparseable version header passes - header presence proves a post-gate build`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", version = "unknown").status)
    }

    @Test
    fun `missing header with mobile token is treated as pre-header build`() = gated("1.1.0") {
        assertEquals(
            HttpStatusCode.UpgradeRequired,
            hit("/api/v2/credits", bearer = token(AuthService.CLIENT_MOBILE)).status,
        )
    }

    @Test
    fun `missing header with plugin token passes`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", bearer = token(AuthService.CLIENT_PLUGIN)).status)
    }

    @Test
    fun `mobile login exchange without header is blocked`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.UpgradeRequired, hit("/api/v2/auth/google", method = HttpMethod.Post).status)
    }

    @Test
    fun `health admin and ssv callback are never gated`() = gated("1.1.0") {
        assertEquals(HttpStatusCode.OK, hit("/healthz").status)
        assertEquals(HttpStatusCode.OK, hit("/api/v2/admin/analytics").status)
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits/admob-ssv").status)
    }

    @Test
    fun `unverifiable token is left to route auth`() = gated("1.1.0") {
        val forged = JWT.create()
            .withIssuer(AuthService.ISSUER)
            .withAudience(AuthService.AUDIENCE)
            .withSubject(UUID.randomUUID().toString())
            .withClaim("client", AuthService.CLIENT_MOBILE)
            .sign(Algorithm.HMAC256("another-secret-another-secret-0000000000"))
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", bearer = forged).status)
    }

    @Test
    fun `gate is off when MIN_APP_VERSION is unset`() = gated(null) {
        assertEquals(HttpStatusCode.OK, hit("/api/v2/credits", version = "1.0.0").status)
    }
}
