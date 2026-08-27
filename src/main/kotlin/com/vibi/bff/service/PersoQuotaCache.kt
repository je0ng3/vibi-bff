package com.vibi.bff.service

import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.service.PersoQuotaCache")

/**
 * Perso 계정(space) 잔여 quota 의 짧은 TTL 캐시.
 *
 * 매 호출마다 외부 조회하면 낭비라 TTL 캐시 — admin 대시보드(`/admin/overview` KPI 카드,
 * 30초 자동갱신)와 모바일 `GET /credits` 의 [isExhausted] (메인화면 "서비스 크레딧 소진" 공지)
 * 가 한 인스턴스를 공유한다. 조회 실패 시 마지막 성공값 유지(있으면), 없으면 null 로 노출.
 */
class PersoQuotaCache(
    private val persoClient: PersoClient,
    private val ttlMs: Long = 60_000,
) {
    /** value=마지막 조회값(실패 반영 안 함), fetchedAtMs=성공 시각. */
    private data class Cached(val value: Long?, val fetchedAtMs: Long)

    private val cache = AtomicReference<Cached?>(null)

    /** 잔여 quota. 조회 실패 시 마지막 성공값(있으면), 한 번도 성공 못 했으면 null. */
    suspend fun remainingQuota(): Long? {
        val now = System.currentTimeMillis()
        val cached = cache.get()
        if (cached != null && now - cached.fetchedAtMs < ttlMs) return cached.value
        return try {
            val fetched = persoClient.getRemainingQuota()
            cache.set(Cached(fetched, now))
            fetched
        } catch (e: Exception) {
            log.warn("Perso remaining quota fetch failed: {}", e.message)
            cached?.value
        }
    }

    /**
     * 서비스 계정 quota 소진 여부. 미확인(null — Perso 조회가 한 번도 성공 못 함)은 fail-open
     * 으로 미소진 취급 — 일시 장애로 모바일에 소진 공지가 잘못 뜨는 것을 막는다.
     */
    suspend fun isExhausted(): Boolean {
        val quota = remainingQuota() ?: return false
        return quota <= 0
    }
}
