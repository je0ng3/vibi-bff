package com.vibi.bff

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.model.AdminAuditEntry
import com.vibi.bff.model.AdminAuditResponse
import com.vibi.bff.model.AdminUserCreditsResponse
import com.vibi.bff.plugins.ROLE_ADMIN
import com.vibi.bff.plugins.ROLE_USER
import com.vibi.bff.plugins.configureErrorHandling
import com.vibi.bff.plugins.configureSerialization
import com.vibi.bff.routes.MAX_ADMIN_GRANT_PER_CALL
import com.vibi.bff.routes.adminRoutes
import com.vibi.bff.service.AdminGrantResult
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.PersoQuotaCache
import com.vibi.bff.service.UserRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `/admin` 라우트 계층 회귀 가드 — 리포지토리는 mock 이고, 여기서 보는 것은 라우트만 아는 것들이다:
 * 입력 검증(금액 범위·사유), [AdminGrantResult] → HTTP 코드 매핑, 권한, 페이지네이션 clamp.
 * 임의 금액 지급의 방어선이 전부 이 층에 있어 리포지토리 테스트로는 대체되지 않는다.
 */
class AdminRoutesTest {

    private val secret = "a".repeat(64)
    private val actorId = UUID.randomUUID()
    private val targetId = UUID.randomUUID()
    private lateinit var adminRepository: AdminRepository
    private lateinit var userRepository: UserRepository
    private lateinit var quotaCache: PersoQuotaCache

    @BeforeTest
    fun setup() {
        adminRepository = mockk()
        userRepository = mockk(relaxed = true)
        quotaCache = mockk(relaxed = true)
    }

    @AfterTest
    fun cleanup() {
        System.clearProperty("ADMIN_GRANT_DAILY_CAP")
        unmockkAll()
    }

    private fun token(role: String, userId: UUID = actorId): String = JWT.create()
        .withIssuer(AuthService.ISSUER)
        .withAudience(AuthService.AUDIENCE)
        .withSubject(userId.toString())
        .withClaim("role", role)
        .sign(Algorithm.HMAC256(secret))

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
        }
        routing {
            route("/api/v2") {
                adminRoutes(adminRepository, quotaCache, userRepository, secret)
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.grant(
        body: String,
        userId: String = targetId.toString(),
        role: String? = ROLE_ADMIN,
    ): HttpResponse = client.post("/api/v2/admin/users/$userId/credits") {
        role?.let { header("Authorization", "Bearer ${token(it)}") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.errorCode(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content

    private fun grantReturns(result: AdminGrantResult) {
        every { adminRepository.grantCredits(any(), any(), any(), any(), any()) } returns result
    }

    // ── POST /admin/users/{userId}/credits — 권한 ────────────────────────────

    @Test
    fun `grant without a token is unauthorized`() = testApp {
        val res = grant("""{"credits":10,"reason":"보상"}""", role = null)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        verify(exactly = 0) { adminRepository.grantCredits(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grant with a non-admin token is forbidden`() = testApp {
        val res = grant("""{"credits":10,"reason":"보상"}""", role = ROLE_USER)
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("admin_required", res.errorCode())
        verify(exactly = 0) { adminRepository.grantCredits(any(), any(), any(), any(), any()) }
    }

    // ── POST /admin/users/{userId}/credits — 입력 검증 ───────────────────────

    @Test
    fun `grant to a malformed user id is rejected`() = testApp {
        val res = grant("""{"credits":10,"reason":"보상"}""", userId = "not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("invalid_user_id", res.errorCode())
    }

    @Test
    fun `grant of zero or negative credits is rejected`() = testApp {
        listOf(0, -5).forEach { credits ->
            val res = grant("""{"credits":$credits,"reason":"보상"}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, "credits=$credits")
            assertEquals("invalid_credits", res.errorCode())
        }
        verify(exactly = 0) { adminRepository.grantCredits(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grant above the per-call ceiling is rejected`() = testApp {
        val res = grant("""{"credits":${MAX_ADMIN_GRANT_PER_CALL + 1},"reason":"보상"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("invalid_credits", res.errorCode())
        verify(exactly = 0) { adminRepository.grantCredits(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grant exactly at the per-call ceiling passes validation`() = testApp {
        grantReturns(AdminGrantResult.Granted(MAX_ADMIN_GRANT_PER_CALL, 500, "admin-1"))
        val res = grant("""{"credits":$MAX_ADMIN_GRANT_PER_CALL,"reason":"보상"}""")
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `grant with a blank reason is rejected - whitespace does not count`() = testApp {
        val res = grant("""{"credits":10,"reason":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("reason_required", res.errorCode())
        verify(exactly = 0) { adminRepository.grantCredits(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grant with an over-long reason is rejected`() = testApp {
        val res = grant("""{"credits":10,"reason":"${"가".repeat(201)}"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("reason_too_long", res.errorCode())
    }

    // ── POST /admin/users/{userId}/credits — 결과 매핑 ───────────────────────

    @Test
    fun `granting returns the new balance and passes the trimmed reason with the actor`() = testApp {
        val target = slot<UUID>()
        val actor = slot<UUID>()
        val reason = slot<String>()
        every {
            adminRepository.grantCredits(capture(target), capture(actor), 10, capture(reason), any())
        } returns AdminGrantResult.Granted(granted = 10, balance = 110, transactionId = "admin-1")

        val res = grant("""{"credits":10,"reason":"  결제 오류 보상  "}""")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(10, body["granted"]!!.jsonPrimitive.content.toInt())
        assertEquals(110, body["balance"]!!.jsonPrimitive.content.toInt())
        assertEquals(targetId, target.captured)
        assertEquals(actorId, actor.captured)
        assertEquals("결제 오류 보상", reason.captured)
    }

    @Test
    fun `granting to an unknown user is a 404`() = testApp {
        grantReturns(AdminGrantResult.TargetNotFound)
        val res = grant("""{"credits":10,"reason":"보상"}""")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `exceeding the daily cap is a 429`() = testApp {
        grantReturns(AdminGrantResult.CapExceeded(grantedRecently = 995, cap = 1000))
        val res = grant("""{"credits":10,"reason":"보상"}""")
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("admin_grant_daily_cap_exceeded", res.errorCode())
    }

    @Test
    fun `the daily cap comes from ADMIN_GRANT_DAILY_CAP and defaults to 1000`() {
        val cap = slot<Int>()
        every {
            adminRepository.grantCredits(any(), any(), any(), any(), capture(cap))
        } returns AdminGrantResult.Granted(10, 10, "admin-1")

        testApp { grant("""{"credits":10,"reason":"보상"}""") }
        assertEquals(1000, cap.captured)

        System.setProperty("ADMIN_GRANT_DAILY_CAP", "42")
        testApp { grant("""{"credits":10,"reason":"보상"}""") }
        assertEquals(42, cap.captured)
    }

    // ── GET /admin/users/{userId}/credits ────────────────────────────────────

    @Test
    fun `credit timeline rejects a malformed user id`() = testApp {
        val res = client.get("/api/v2/admin/users/nope/credits") {
            header("Authorization", "Bearer ${token(ROLE_ADMIN)}")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("invalid_user_id", res.errorCode())
    }

    @Test
    fun `credit timeline clamps limit into 1 to 200 and floors a negative offset`() = testApp {
        val limit = slot<Int>()
        val offset = slot<Int>()
        every {
            adminRepository.getUserCredits(targetId, capture(limit), capture(offset))
        } returns AdminUserCreditsResponse(balance = 0, events = emptyList(), total = 0, hasMerges = false)

        client.get("/api/v2/admin/users/$targetId/credits?limit=9999&offset=-3") {
            header("Authorization", "Bearer ${token(ROLE_ADMIN)}")
        }
        assertEquals(200, limit.captured)
        assertEquals(0, offset.captured)
    }

    // ── GET /admin/audit ─────────────────────────────────────────────────────

    @Test
    fun `audit log requires an admin token`() = testApp {
        val res = client.get("/api/v2/admin/audit") {
            header("Authorization", "Bearer ${token(ROLE_USER)}")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `audit log returns the repository page`() = testApp {
        every { adminRepository.listAudit(50, 0) } returns AdminAuditResponse(
            entries = listOf(
                AdminAuditEntry(
                    id = 1,
                    at = "2026-09-08T00:00:00Z",
                    actorEmail = "admin@example.com",
                    action = "credit_grant",
                    targetUserId = targetId.toString(),
                    targetEmail = "user@example.com",
                    amount = 10,
                    detail = "결제 오류 보상",
                ),
            ),
            total = 1,
        )

        val res = client.get("/api/v2/admin/audit") {
            header("Authorization", "Bearer ${token(ROLE_ADMIN)}")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(1, body["total"]!!.jsonPrimitive.content.toInt())
        val entry = body["entries"]!!.jsonArray.single().jsonObject
        assertEquals("credit_grant", entry["action"]!!.jsonPrimitive.content)
        assertEquals("결제 오류 보상", entry["detail"]!!.jsonPrimitive.content)
    }
}
