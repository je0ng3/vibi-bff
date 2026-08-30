package com.vibi.bff

import com.vibi.bff.db.RenderJobsTable
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.JobAnalyticsRepository
import com.vibi.bff.service.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * [JobAnalyticsRepository.reapStuckRenderJobs] — 인스턴스 교체/크래시로 PROCESSING 에 멈춘
 * 유령 렌더 잡 정리. 이게 없으면 admin "진행 중 작업"에 경과시간이 무한히 자라는 row 가 남는다.
 */
class JobAnalyticsRepositoryTest {

    private val testDb = TestDatabase()
    private val repo = JobAnalyticsRepository()
    private lateinit var userId: UUID

    @BeforeTest
    fun setup() {
        testDb.start()
        // render_jobs.user_id 는 users FK — 실제 계정 row 가 있어야 insert 가 통과한다.
        userId = UserRepository().upsert(AuthProvider.GOOGLE, "g-render", "r@example.com", "R", null).id
    }

    @AfterTest
    fun teardown() = testDb.stop()

    /** PROCESSING 렌더 잡 1건을 넣고 created_at 을 [ageMinutes] 분 전으로 밀어 유령 상태를 재현. */
    private fun insertProcessingJob(jobId: String, ageMinutes: Long) = runBlocking {
        repo.insertRenderJob(jobId, userId, sourceDurationMs = 1000L, status = "PROCESSING")
        transaction {
            RenderJobsTable.update({ RenderJobsTable.id eq jobId }) {
                it[createdAt] = Instant.now().minus(Duration.ofMinutes(ageMinutes))
            }
        }
    }

    private fun statusOf(jobId: String): String = transaction {
        RenderJobsTable.selectAll().where { RenderJobsTable.id eq jobId }.single()[RenderJobsTable.status]
    }

    @Test
    fun `marks a render job stuck past the threshold as FAILED with finishedAt`() = runBlocking {
        insertProcessingJob("render-stuck", ageMinutes = 90)

        val reaped = repo.reapStuckRenderJobs(Duration.ofHours(1))

        assertEquals(listOf("render-stuck"), reaped)
        assertEquals("FAILED", statusOf("render-stuck"))
        // finished_at 이 채워져야 대시보드 "진행 중" 목록에서 빠지고 소요시간 집계가 닫힌다.
        val finished = transaction {
            RenderJobsTable.selectAll().where { RenderJobsTable.id eq "render-stuck" }
                .single()[RenderJobsTable.finishedAt]
        }
        assertTrue(finished != null)
    }

    @Test
    fun `leaves a young job running and does not touch terminal rows`() = runBlocking {
        insertProcessingJob("render-young", ageMinutes = 5)
        insertProcessingJob("render-done", ageMinutes = 300)
        repo.updateRenderJobStatus("render-done", "COMPLETED")

        val reaped = repo.reapStuckRenderJobs(Duration.ofHours(1))

        assertTrue(reaped.isEmpty())
        assertEquals("PROCESSING", statusOf("render-young"))
        // 이미 끝난 잡은 나이와 무관 — COMPLETED 를 FAILED 로 되돌리지 않는다.
        assertEquals("COMPLETED", statusOf("render-done"))
    }

    @Test
    fun `a late completion overwrites the reaped status so slow renders self-heal`() = runBlocking {
        insertProcessingJob("render-late", ageMinutes = 90)
        repo.reapStuckRenderJobs(Duration.ofHours(1))
        assertEquals("FAILED", statusOf("render-late"))

        // reaper 이후 실제로 끝난 잡은 정상 종료 갱신이 이겨야 한다(자기 교정).
        repo.updateRenderJobStatus("render-late", "COMPLETED")
        assertEquals("COMPLETED", statusOf("render-late"))
    }
}
