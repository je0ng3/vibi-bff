package com.vibi.bff

import com.vibi.bff.config.PersoConfig
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.PersoQuotaCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [PersoQuotaCache] — 예비분 임계값 판정 + TTL 캐시 + 조회 실패 fail-open.
 *
 * `GET /credits` 의 `separationAvailable` 이 여기서 나오고, 그 플래그가 모바일 공지와
 * 분리 시작 차단을 좌우하므로 경계값을 테스트로 고정한다.
 */
class PersoQuotaCacheTest {

    private val persoConfig = PersoConfig(
        apiKey = "pk_test_abc",
        baseUrl = "https://api.perso.ai",
        storageBaseUrl = "https://portal-media.perso.ai",
        spaceSeq = 42,
        pollIntervalMs = 1000,
        maxPollMinutes = 5,
        downloadAllowedHosts = setOf("portal-media.perso.ai"),
    )

    /** quota 를 [quota] 로 응답하는 client. [calls] 로 실제 외부 호출 횟수를 관측한다. */
    private fun clientReturning(quota: Long, calls: AtomicInteger = AtomicInteger()): PersoClient {
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(
                content = """{"result":{"remainingQuota":{"remainingQuota":$quota}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return PersoClient(persoConfig, HttpClient(engine) { install(ContentNegotiation) { json(AppJson) } })
    }

    /**
     * 조회가 실패하는 client. 4xx 를 쓰는 이유 — [PersoClient] 의 transient retry 는 5xx 를
     * 3초 간격 3회 재시도하므로 500 으로 테스트하면 호출당 9초를 대기로만 태운다. 4xx 는 즉시
     * 던지고(재시도 안 함) 캐시 입장에선 동일한 "조회 실패" 경로다.
     */
    private fun failingClient(): PersoClient {
        val engine = MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) }
        return PersoClient(persoConfig, HttpClient(engine) { install(ContentNegotiation) { json(AppJson) } })
    }

    @Test
    fun `quota at or below reserve blocks, above reserve allows`() = runBlocking {
        val reserve = PersoQuotaCache.DEFAULT_RESERVE
        // 경계: 임계값과 같으면 차단(<=), 하나 위면 허용.
        assertTrue(PersoQuotaCache(clientReturning(reserve)).isBelowReserve())
        assertFalse(PersoQuotaCache(clientReturning(reserve + 1)).isBelowReserve())
        assertTrue(PersoQuotaCache(clientReturning(0)).isBelowReserve())
    }

    @Test
    fun `fetch failure is fail-open so the notice does not fire on a transient outage`() = runBlocking {
        val cache = PersoQuotaCache(failingClient())
        assertFalse(cache.isBelowReserve())
        assertEquals(null, cache.remainingQuota())
    }

    @Test
    fun `within TTL the quota is served from cache without another upstream call`() = runBlocking {
        val calls = AtomicInteger()
        val cache = PersoQuotaCache(clientReturning(100, calls), ttlMs = 60_000)
        repeat(3) { cache.remainingQuota() }
        assertEquals(1, calls.get())
    }

    /**
     * 만료된 캐시 읽기는 **새 값을 기다리지 않는다** — 캐시값을 즉시 돌려주고 갱신은 백그라운드로.
     * 이게 admin overview / 모바일 GET /credits 가 Perso 왕복에 매달리지 않는 근거다.
     */
    @Test
    fun `stale read returns the cached value immediately and refreshes in background`() = runBlocking {
        val quota = AtomicLong(100)
        val engine = MockEngine {
            respond(
                content = """{"result":{"remainingQuota":{"remainingQuota":${quota.get()}}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PersoClient(persoConfig, HttpClient(engine) { install(ContentNegotiation) { json(AppJson) } })
        // ttl=0 → 첫 조회 직후부터 항상 만료 상태.
        val cache = PersoQuotaCache(client, ttlMs = 0)

        assertEquals(100L, cache.remainingQuota()) // 부팅 직후 1회만 동기 조회
        quota.set(200)

        // 만료됐지만 upstream 을 기다리지 않으므로 아직 이전 값이 보인다.
        assertEquals(100L, cache.remainingQuota())

        // 백그라운드 갱신이 끝나면 새 값이 반영된다.
        val deadline = System.currentTimeMillis() + 5_000
        var latest: Long? = null
        while (System.currentTimeMillis() < deadline) {
            latest = cache.remainingQuota()
            if (latest == 200L) break
            delay(20)
        }
        assertEquals(200L, latest)
    }

    /** Perso 가 죽어도 마지막 성공값을 계속 서빙해야 공지/게이트가 장애로 뒤집히지 않는다. */
    @Test
    fun `a failed refresh keeps serving the last successful value`() = runBlocking {
        val fail = AtomicBoolean(false)
        val engine = MockEngine {
            if (fail.get()) respond(content = "nope", status = HttpStatusCode.NotFound)
            else respond(
                content = """{"result":{"remainingQuota":{"remainingQuota":123}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PersoClient(persoConfig, HttpClient(engine) { install(ContentNegotiation) { json(AppJson) } })
        val cache = PersoQuotaCache(client, ttlMs = 0)

        assertEquals(123L, cache.remainingQuota())
        fail.set(true)

        // 갱신이 계속 실패해도 이전 성공값 유지 — null 로 무너지지 않는다.
        repeat(5) { delay(20); assertEquals(123L, cache.remainingQuota()) }
    }

    @Test
    fun `threshold is configurable independently of the default`() = runBlocking {
        // 운영 중 임계값을 바꿔야 할 때 판정만 갈아끼울 수 있는지 — 100 예비분이면 100 은 차단.
        assertTrue(PersoQuotaCache(clientReturning(100), reserveThreshold = 100).isBelowReserve())
        assertFalse(PersoQuotaCache(clientReturning(100), reserveThreshold = 99).isBelowReserve())
    }
}
