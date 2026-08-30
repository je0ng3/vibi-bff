package com.vibi.bff.service

import com.vibi.bff.db.RenderJobsTable
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * render_jobs Postgres write 만 담당 (separation_jobs 는 [SeparationQueueRepository] 가
 * source-of-truth 로 owns). RenderService 가 status 변화 시점에 호출 — admin 대시보드 source.
 *
 * **Non-fatal**: 모든 메서드가 try/catch 로 감싸 transaction 실패가 ffmpeg 파이프라인을
 * 깨뜨리지 않도록 한다. 분석 데이터는 보조 정보 — DB 가 잠시 끊겨도 user-facing 동작은 계속.
 *
 * 모든 write 는 [Dispatchers.IO] 로 wrap — Ktor request coroutine 이 Netty 이벤트 루프에서
 * 호출해도 JDBC 가 루프를 막지 않게.
 */
class JobAnalyticsRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val STATUS_PROCESSING = "PROCESSING"
        private const val STATUS_FAILED = "FAILED"

        /**
         * 렌더 잡이 이 시간 이상 PROCESSING 이면 유령으로 간주. 모바일 폴링 포기 시한(15분)의
         * 4배 — 정상 렌더가 걸릴 수 있는 최대치(ffmpeg step 당 30분 cap)를 넘기면서도 유령 row 가
         * 대시보드에 며칠씩 남지 않는 선.
         */
        val STUCK_RENDER_THRESHOLD: Duration = Duration.ofHours(1)
    }

    suspend fun insertRenderJob(
        jobId: String,
        userId: UUID,
        sourceDurationMs: Long,
        status: String,
    ) = safeWrite("insertRenderJob jobId=$jobId") {
        RenderJobsTable.insert {
            it[RenderJobsTable.id] = jobId
            it[RenderJobsTable.userId] = userId
            it[RenderJobsTable.sourceDurationMs] = sourceDurationMs
            it[RenderJobsTable.status] = status
            it[RenderJobsTable.createdAt] = Instant.now()
        }
    }

    suspend fun updateRenderJobStatus(jobId: String, status: String) =
        safeWrite("updateRenderJobStatus jobId=$jobId status=$status") {
            val terminal = status == "COMPLETED" || status == "FAILED"
            val now = Instant.now()
            RenderJobsTable.update({ RenderJobsTable.id eq jobId }) {
                it[RenderJobsTable.status] = status
                if (terminal) it[RenderJobsTable.finishedAt] = now
            }
        }

    /**
     * PROCESSING 인 채 [olderThan] 이상 방치된 render_jobs row 를 FAILED 로 마킹.
     *
     * 렌더 잡은 **인스턴스 메모리**(RenderService.jobs)에서 돌고 DB row 는 시작/종료 시점에만
     * 갱신된다. 따라서 Cloud Run 인스턴스가 렌더 도중 교체·크래시되면 종료 갱신이 영영 실행되지
     * 않아 row 가 PROCESSING 으로 남고, admin 대시보드 "진행 중 작업"에 경과시간이 무한히 자라는
     * 유령 잡으로 보인다 (실측 146시간). 모바일은 15분에 폴링을 포기하므로(RenderJobPolling
     * MAX_POLL_TOTAL_MS) 그 시점 이후의 row 는 어차피 아무도 기다리지 않는 잔재다.
     *
     * 아직 살아 있는 잡을 잘못 건드려도 안전하다 — UPDATE 조건에 status='PROCESSING' 을 걸어
     * 이미 전이된 row 는 제외하고, 늦게 끝난 잡은 [updateRenderJobStatus] 가 COMPLETED 로
     * 덮어써 자기 교정된다.
     *
     * 반환: FAILED 로 전이된 jobId 목록 (DB 오류 시 빈 목록 — 분석 write 는 non-fatal).
     */
    suspend fun reapStuckRenderJobs(olderThan: Duration): List<String> = try {
        withContext(Dispatchers.IO) {
            transaction {
                val cutoff = Instant.now().minus(olderThan)
                val stuckIds = RenderJobsTable
                    .select(RenderJobsTable.id)
                    .where {
                        (RenderJobsTable.status eq STATUS_PROCESSING) and
                            (RenderJobsTable.createdAt less cutoff)
                    }
                    .map { it[RenderJobsTable.id] }
                val reaped = stuckIds.filter { id ->
                    RenderJobsTable.update({
                        (RenderJobsTable.id eq id) and (RenderJobsTable.status eq STATUS_PROCESSING)
                    }) {
                        it[RenderJobsTable.status] = STATUS_FAILED
                        it[RenderJobsTable.finishedAt] = Instant.now()
                    } > 0
                }
                if (reaped.isNotEmpty()) {
                    log.warn(
                        "Reaped stuck PROCESSING render jobs (older than {}): count={} ids={}",
                        olderThan, reaped.size, reaped,
                    )
                }
                reaped
            }
        }
    } catch (e: Exception) {
        log.warn("reapStuckRenderJobs failed ({}: {})", e.javaClass.simpleName, e.message)
        emptyList()
    }

    private suspend inline fun safeWrite(label: String, crossinline block: () -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                transaction { block() }
            }
        } catch (e: Exception) {
            log.warn("Analytics write failed: {} ({}: {})", label, e.javaClass.simpleName, e.message)
        }
    }
}
