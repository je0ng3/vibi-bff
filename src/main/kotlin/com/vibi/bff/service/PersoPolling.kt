package com.vibi.bff.service

import com.vibi.bff.plugins.PersoApiException
import com.vibi.bff.plugins.PersoJobFailedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Poll Perso for project completion. Used by SeparationService.
 *
 * Each tick the caller's [onProgress] is invoked so the service can
 * update its own job's progress/reason fields. Returns when Perso
 * reports `Completed`; throws on failure or [maxPollMinutes] timeout.
 */
internal suspend fun pollPersoUntilComplete(
    persoClient: PersoClient,
    scope: CoroutineScope,
    projectSeq: Long,
    pollIntervalMs: Long,
    maxPollMinutes: Int,
    onProgress: (progress: Int, reason: String?) -> Unit,
) {
    val deadline = System.currentTimeMillis() + maxPollMinutes * 60_000L
    while (scope.isActive) {
        if (System.currentTimeMillis() > deadline) {
            throw RuntimeException("Perso polling timed out after $maxPollMinutes minutes")
        }
        val p = persoClient.getProgress(projectSeq)
        onProgress(p.progress, p.progressReason)
        when {
            // progressReason="Failed" (문서화된 시그니처: hasFailed=false + 100% Failed) 는 입력이
            // 원인인 사용자 조치 가능 실패 (오디오 트랙 부재 / 비호환 코덱). ERROR/500 이 아니라 안내
            // 문구 + 크레딧 환불로 다룬다 ([executePipeline] catch 가 PersoJobFailedException 분기).
            p.progressReason == "Failed" ->
                throw PersoJobFailedException(
                    code = "no_audio_detected",
                    userMessage = "Couldn't isolate audio. Make sure the video or file contains an audio track, then try again.",
                )
            // hasFailed=true (Failed reason 없이) 는 Perso 측 진짜 잡 실패/장애 — 사용자 조치로
            // 회복 불가. 인프라 오류 경로(ERROR/Sentry)로 보내 ops 가 systemic 장애를 인지하게 한다.
            p.hasFailed ->
                throw PersoApiException(500, "Perso job failed (hasFailed=true) at progress=${p.progress}")
            p.progressReason == "Completed" -> return
        }
        delay(pollIntervalMs)
    }
}
