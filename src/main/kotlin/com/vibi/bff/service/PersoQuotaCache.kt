package com.vibi.bff.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.service.PersoQuotaCache")

/**
 * Perso 계정(space) 잔여 quota 캐시 — **요청 경로가 외부 호출을 기다리지 않도록** stale-while-
 * revalidate 로 동작한다.
 *
 * 소비자: admin 대시보드(`/admin/overview` KPI, 30초 자동갱신)와 모바일 `GET /credits`
 * ([isBelowReserve] → 메인화면 공지 + 분리 시작 게이트). 둘이 한 인스턴스를 공유한다.
 *
 * **왜 stale-while-revalidate 인가** — 예전처럼 TTL 만료 시 응답 안에서 Perso 를 동기 호출하면,
 * 30초 갱신 × 60초 TTL 이라 두 번에 한 번은 외부 왕복이 응답 시간에 그대로 실린다. 게다가 공용
 * httpClient 는 Perso 미디어 작업 기준(request timeout 600초)이라, Perso 가 느려지면 대시보드와
 * 모바일 크레딧 조회가 수 분씩 매달린다. 그래서:
 *
 *   - 캐시된 값이 있으면 **항상 즉시 반환** — 만료됐으면 백그라운드 갱신만 예약(단일 비행).
 *   - 캐시가 아예 없는 부팅 직후 1회만 동기 조회하되 [fetchTimeoutMs] 로 짧게 끊는다.
 *   - 조회 실패는 마지막 성공값을 유지하고 시도 시각만 갱신 — Perso 장애 시 매 요청이 다시
 *     동기 조회로 빠지지 않는다.
 */
class PersoQuotaCache(
    private val persoClient: PersoClient,
    private val ttlMs: Long = 60_000,
    /** 예비분 임계값 — 잔여 quota 가 이 값 이하면 신규 분리를 막는다. [DEFAULT_RESERVE] 참조. */
    private val reserveThreshold: Long = DEFAULT_RESERVE,
    /** 부팅 직후 동기 조회의 상한. 공용 httpClient 의 600초 timeout 을 요청 경로에 노출하지 않는다. */
    private val fetchTimeoutMs: Long = DEFAULT_FETCH_TIMEOUT_MS,
    /** 백그라운드 갱신 scope. 테스트가 갈아끼울 수 있게 주입 가능. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * [value] = 마지막 **성공** 조회값 (실패해도 유지, 한 번도 성공 못 했으면 null).
     * [attemptedAtMs] = 마지막 시도 시각 (성공/실패 무관) — TTL 판정 기준이라 실패도 갱신해야
     * 장애 중 매 요청이 재조회로 몰리지 않는다.
     */
    private data class Snapshot(val value: Long?, val attemptedAtMs: Long)

    private val snapshot = AtomicReference<Snapshot?>(null)

    /** 백그라운드 갱신 단일 비행 — 동시 요청이 Perso 를 동시에 두드리지 않게. */
    private val refreshing = AtomicBoolean(false)

    /**
     * 잔여 quota. 캐시가 있으면 만료 여부와 무관하게 즉시 반환하고(만료면 백그라운드 갱신 예약),
     * 캐시가 없을 때만 [fetchTimeoutMs] 내에서 1회 동기 조회한다.
     *
     * 반환 null = 아직 한 번도 조회에 성공하지 못함 (호출자는 fail-open 으로 다룬다).
     */
    suspend fun remainingQuota(): Long? {
        val current = snapshot.get()
            ?: return fetchAndStore() // 부팅 직후 1회 — 이후로는 이 경로로 오지 않는다.
        if (System.currentTimeMillis() - current.attemptedAtMs >= ttlMs) scheduleRefresh()
        return current.value
    }

    /**
     * 서비스 계정 quota 가 예비분([reserveThreshold]) 이하인지. 완전 소진(0)이 아니라 여유가
     * 남았을 때 미리 막는 이유: 진행 중인 잡이 quota 를 마저 쓰는 동안 신규 잡이 중간에 실패해
     * (사용자 크레딧은 선차감) 버리는 것을 방지하는 버퍼.
     *
     * 미확인(null — 조회가 한 번도 성공 못 함)은 fail-open 으로 여유 있음 취급 — 일시 장애로
     * 모바일에 공지가 잘못 뜨고 분리가 막히는 것을 방지.
     */
    suspend fun isBelowReserve(): Boolean {
        val quota = remainingQuota() ?: return false
        return quota <= reserveThreshold
    }

    /** 만료된 캐시를 백그라운드로 갱신. 이미 갱신 중이면 no-op — 요청은 어차피 기다리지 않는다. */
    private fun scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                fetchAndStore()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * 1회 조회 후 [snapshot] 갱신. 실패(타임아웃 포함)해도 마지막 성공값은 보존하고 시도 시각만
     * 갱신한다. 반환은 갱신 후의 유효값 (실패 시 이전 성공값 또는 null).
     */
    private suspend fun fetchAndStore(): Long? {
        val fetched = try {
            withTimeoutOrNull(fetchTimeoutMs) { persoClient.getRemainingQuota() }
                .also { if (it == null) log.warn("Perso remaining quota fetch timed out after {}ms", fetchTimeoutMs) }
        } catch (e: Exception) {
            log.warn("Perso remaining quota fetch failed: {}", e.message)
            null
        }
        val now = System.currentTimeMillis()
        // 실패면 직전 성공값 유지 — updateAndGet 으로 동시 갱신과의 경합에서도 값이 사라지지 않게.
        // updateAndGet 의 반환 타입은 nullable(AtomicReference<Snapshot?>)이지만 람다가 항상
        // non-null 을 만들므로 실제로 null 이 나오지 않는다.
        return snapshot.updateAndGet { prev ->
            Snapshot(value = fetched ?: prev?.value, attemptedAtMs = now)
        }?.value
    }

    companion object {
        /** 기본 예비분 — 이 값 이하로 떨어지면 모바일에 공지 + 신규 분리 차단. */
        const val DEFAULT_RESERVE: Long = 60

        /**
         * 부팅 직후 동기 조회 상한. quota 조회는 단순 status GET 이라 정상이면 수백 ms —
         * 5초를 넘기면 값 없이(fail-open) 진행하고 백그라운드 갱신에 맡기는 편이 낫다.
         */
        const val DEFAULT_FETCH_TIMEOUT_MS: Long = 5_000
    }
}
