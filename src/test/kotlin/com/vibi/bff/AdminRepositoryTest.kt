package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * H2 PostgreSQL mode 로 Flyway 마이그레이션 + raw SQL 같은 경로 검증.
 * 신규 surface (수익/IAP, 잡 성공·실패 분해) 의 집계 정확성 회귀 가드.
 *
 * H2 는 V9 의 hash-named CHECK 제약이 'admin' platform 을 거부하므로 admin-grant 케이스는
 * 여기서 커버하지 않는다 (adminGrantedCredits=0 만 확인). apple/google 경로는 정상.
 */
class AdminRepositoryTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var users: UserRepository
    private lateinit var credits: CreditRepository
    private lateinit var admin: AdminRepository

    @BeforeTest
    fun setup() {
        val unique = "test_" + System.nanoTime()
        dataSource = DbBootstrap.init(
            DbConfig(
                jdbcUrl = "jdbc:h2:mem:$unique;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
                maxPoolSize = 2,
            )
        )
        users = UserRepository()
        credits = CreditRepository()
        admin = AdminRepository()
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    private fun insertRenderJob(userId: UUID, status: String) = transaction {
        val id = "render-" + UUID.randomUUID()
        exec(
            "INSERT INTO render_jobs (id, user_id, source_duration_ms, status) " +
                "VALUES ('$id', CAST('$userId' AS UUID), 1000, '$status')",
        )
    }

    private fun insertSeparationJob(
        userId: UUID,
        status: String,
        client: String = "mobile",
        durationMs: Long = 1000,
    ) = transaction {
        val id = "sep-" + UUID.randomUUID()
        exec(
            "INSERT INTO separation_jobs (id, user_id, source_duration_ms, status, client) " +
                "VALUES ('$id', CAST('$userId' AS UUID), $durationMs, '$status', '$client')",
        )
    }

    /**
     * credit_transactions 에 직접 INSERT — platform/created_at 을 명시 제어한다.
     * created_at 은 timestamptz 라 파라미터(Instant)로 바인딩. CreditRepository.grantPurchase
     * 는 created_at=now 고정 + platform CHECK(apple/google) 이라 과거시각·admin 케이스 못 만듦.
     */
    private fun insertTxn(userId: UUID, platform: String, txId: String, credits: Int, createdAt: Instant) = transaction {
        exec(
            "INSERT INTO credit_transactions (user_id, platform, transaction_id, product_id, credits, created_at) " +
                "VALUES (CAST('$userId' AS UUID), '$platform', '$txId', 'vibi.credits.test', $credits, ?)",
            args = listOf<Pair<IColumnType<*>, Any?>>(JavaInstantColumnType() to createdAt),
        )
    }

    /**
     * H2 의 platform CHECK 제약을 제거해 platform='admin' INSERT 를 허용한다.
     * V9 가 named 제약을 admin 허용으로 재생성하지만 V5 의 hash-named 원본 제약이 H2 에 잔존해
     * 여전히 admin 을 거부한다(V9 주석). CHECK_CLAUSE 에 'apple' 이 들어간 제약을 모두 드롭 —
     * credits>0 제약은 'apple' 을 포함 안 해 보존된다.
     */
    private fun allowAdminPlatformInH2() = transaction {
        val names = mutableListOf<String>()
        exec(
            "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CHECK_CLAUSE LIKE '%apple%'",
        ) { rs -> while (rs.next()) names += rs.getString(1) }
        names.forEach { exec("ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS \"$it\"") }
    }

    // ── getJobStatusBreakdown ────────────────────────────────────────────────

    @Test
    fun `job status breakdown counts success failure in-progress per type`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        // render: 성공=COMPLETED, 나머지 진행중/실패
        insertRenderJob(u.id, "COMPLETED")
        insertRenderJob(u.id, "COMPLETED")
        insertRenderJob(u.id, "FAILED")
        insertRenderJob(u.id, "PROCESSING")
        // separation: 성공=READY (COMPLETED 아님!), 진행중은 QUEUED/SUBMITTING/PROCESSING.
        // 클라이언트별 행 분리 — mobile 4 + plugin 2.
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY", client = "plugin")
        insertSeparationJob(u.id, "FAILED", client = "plugin")
        insertSeparationJob(u.id, "QUEUED")
        insertSeparationJob(u.id, "PROCESSING")

        val rows = admin.getJobStatusBreakdown()
        assertEquals(listOf("render", "separation", "separation"), rows.map { it.jobType })
        assertEquals(listOf(null, "mobile", "plugin"), rows.map { it.client })

        val render = rows.first { it.jobType == "render" }
        assertEquals(4, render.total)
        assertEquals(2, render.succeeded)
        assertEquals(1, render.failed)
        assertEquals(1, render.inProgress)

        val sepMobile = rows.first { it.jobType == "separation" && it.client == "mobile" }
        assertEquals(4, sepMobile.total)
        assertEquals(2, sepMobile.succeeded) // READY 만 성공으로 카운트
        assertEquals(0, sepMobile.failed)
        assertEquals(2, sepMobile.inProgress) // QUEUED + PROCESSING

        val sepPlugin = rows.first { it.jobType == "separation" && it.client == "plugin" }
        assertEquals(2, sepPlugin.total)
        assertEquals(1, sepPlugin.succeeded)
        assertEquals(1, sepPlugin.failed)
        assertEquals(0, sepPlugin.inProgress)
    }

    @Test
    fun `job status breakdown returns zeroed rows on empty db`() {
        val rows = admin.getJobStatusBreakdown()
        assertEquals(3, rows.size) // render + separation(mobile) + separation(plugin)
        rows.forEach {
            assertEquals(0, it.total)
            assertEquals(0, it.succeeded)
            assertEquals(0, it.failed)
            assertEquals(0, it.inProgress)
        }
    }

    // ── 클라이언트(mobile/plugin) 분리 집계 ─────────────────────────────────

    @Test
    fun `overview splits separations by client`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY", client = "plugin")

        val o = admin.getOverview()
        assertEquals(3, o.totalSeparations)
        assertEquals(2, o.mobileSeparations)
        assertEquals(1, o.pluginSeparations)
    }

    @Test
    fun `overview sums current credit balance and drops when consumed`() {
        val u1 = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val u2 = users.upsert(AuthProvider.APPLE, "a-2", "b@example.com", "B", null)
        credits.grantPurchase(u1.id, "apple", "tx-1", "vibi.credits.50", 50)
        credits.grantPurchase(u2.id, "google", "tx-2", "vibi.credits.30", 30)
        assertEquals(80, admin.getOverview().totalUserCredits) // 50 + 30

        // 소비하면 그만큼 총합이 줄어든다.
        credits.reserve(u1.id, "job-1", 20)
        assertEquals(60, admin.getOverview().totalUserCredits) // 80 - 20
    }

    @Test
    fun `overview credit total is zero on empty db`() {
        assertEquals(0, admin.getOverview().totalUserCredits)
    }

    @Test
    fun `overview averages separation length overall and by client`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        // mobile: 2000, 4000 → 평균 3000. plugin: 10000 → 평균 10000. 전체 3건 평균 = 16000/3 = 5333.
        insertSeparationJob(u.id, "READY", client = "mobile", durationMs = 2000)
        insertSeparationJob(u.id, "READY", client = "mobile", durationMs = 4000)
        insertSeparationJob(u.id, "READY", client = "plugin", durationMs = 10000)

        val o = admin.getOverview()
        assertEquals(5333, o.avgSeparationDurationMs)        // (2000+4000+10000)/3 절삭
        assertEquals(3000, o.avgMobileSeparationDurationMs)  // (2000+4000)/2
        assertEquals(10000, o.avgPluginSeparationDurationMs) // 10000/1
    }

    @Test
    fun `overview separation averages are zero on empty db`() {
        val o = admin.getOverview()
        assertEquals(0, o.avgSeparationDurationMs)
        assertEquals(0, o.avgMobileSeparationDurationMs)
        assertEquals(0, o.avgPluginSeparationDurationMs)
    }

    // getDailyStats 는 generate_series 사용으로 Postgres 전용 — H2 테스트 불가.
    // 클라이언트 분리 집계(SUM CASE WHEN client='plugin')는 breakdown/users 테스트가 동일 패턴 검증.

    @Test
    fun `users overview splits separation counts and filters by client`() {
        val mobileUser = users.upsert(AuthProvider.GOOGLE, "g-1", "m@example.com", "Mobile", null)
        val pluginUser = users.upsert(AuthProvider.GOOGLE, "g-2", "p@example.com", "Plugin", null)
        val idleUser = users.upsert(AuthProvider.GOOGLE, "g-3", "i@example.com", "Idle", null)
        insertSeparationJob(mobileUser.id, "READY")
        insertRenderJob(mobileUser.id, "COMPLETED")
        insertSeparationJob(pluginUser.id, "READY", client = "plugin")
        insertSeparationJob(pluginUser.id, "FAILED", client = "plugin")

        // 필터 없음 — 전원 + per-user 분해.
        val (all, allTotal) = admin.getUsersOverview(50, 0, null)
        assertEquals(3, allTotal)
        val m = all.first { it.email == "m@example.com" }
        assertEquals(1, m.mobileSeparations)
        assertEquals(0, m.pluginSeparations)
        val p = all.first { it.email == "p@example.com" }
        assertEquals(0, p.mobileSeparations)
        assertEquals(2, p.pluginSeparations)

        // client=plugin — plugin 분리 이력 사용자만.
        val (pluginRows, pluginTotal) = admin.getUsersOverview(50, 0, null, client = "plugin")
        assertEquals(1, pluginTotal)
        assertEquals("p@example.com", pluginRows.single().email)

        // client=mobile — render 또는 mobile 분리 이력 사용자만 (잡 없는 idle 은 제외).
        val (mobileRows, mobileTotal) = admin.getUsersOverview(50, 0, null, client = "mobile")
        assertEquals(1, mobileTotal)
        assertEquals("m@example.com", mobileRows.single().email)
    }

    @Test
    fun `users overview lists linked providers primary and secondary`() {
        val solo = users.upsert(AuthProvider.GOOGLE, "g-solo", "solo@example.com", "Solo", null)
        val linked = users.upsert(AuthProvider.GOOGLE, "g-link", "link@example.com", "Linked", null)
        // 두 번째 provider 연결 → 이 계정은 google(primary) + apple(secondary).
        users.linkOrMerge(linked.id, AuthProvider.APPLE, "ap-link", "link@icloud.com", "Linked", null)

        val (all, _) = admin.getUsersOverview(50, 0, null)
        // 통합 안 한 계정은 provider 1개.
        assertEquals(listOf("google"), all.first { it.userId == solo.id.toString() }.linkedProviders)
        // 통합한 계정은 primary + secondary 둘 다.
        assertEquals(
            setOf("google", "apple"),
            all.first { it.userId == linked.id.toString() }.linkedProviders.toSet(),
        )
    }

    // ── getAdStats ─────────────────────────────────────────────────────────

    @Test
    fun `ad stats counts admob watches total 30d and distinct users`() {
        // platform CHECK 제약을 풀어 admob INSERT 허용 (helper 이름은 admin 이지만 whitelist 전체 드롭).
        allowAdminPlatformInH2()
        val u1 = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val u2 = users.upsert(AuthProvider.APPLE, "a-2", "b@example.com", "B", null)
        val now = Instant.now()
        insertTxn(u1.id, "admob", "ad-1", 1, now)
        insertTxn(u1.id, "admob", "ad-2", 1, now)
        insertTxn(u2.id, "admob", "ad-3", 1, now.minusSeconds(40L * 24 * 3600)) // 40일 전
        insertTxn(u1.id, "apple", "tx-1", 50, now) // 결제 — 광고 집계에서 제외돼야 함

        val s = admin.getAdStats()
        assertEquals(3, s.totalWatches)   // admob 3건만 (apple 제외)
        assertEquals(2, s.watches30d)     // 40일 전 1건 제외
        assertEquals(2, s.watchingUsers)  // u1, u2 distinct
    }

    @Test
    fun `ad stats is all zeros on empty db`() {
        val s = admin.getAdStats()
        assertEquals(0, s.totalWatches)
        assertEquals(0, s.watches30d)
        assertEquals(0, s.watchingUsers)
    }

    // ── setUserRole ──────────────────────────────────────────────────────────

    @Test
    fun `setUserRole promotes then demotes and returns update count`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        assertEquals("user", currentRole(u.id))

        assertEquals(1, admin.setUserRole(u.id, "admin"))
        assertEquals("admin", currentRole(u.id))

        assertEquals(1, admin.setUserRole(u.id, "user"))
        assertEquals("user", currentRole(u.id))
    }

    @Test
    fun `setUserRole returns zero for unknown user`() {
        assertEquals(0, admin.setUserRole(UUID.randomUUID(), "admin"))
    }

    private fun currentRole(userId: UUID): String = transaction {
        var role = ""
        exec("SELECT role FROM users WHERE id = CAST('$userId' AS UUID)") { rs ->
            if (rs.next()) role = rs.getString(1)
        }
        role
    }

    // ── getRecentHealth ──────────────────────────────────────────────────────

    private fun insertCall(provider: String, endpoint: String, success: Boolean, latencyMs: Long) = transaction {
        exec(
            "INSERT INTO external_api_calls (provider, endpoint, success, latency_ms) " +
                "VALUES ('$provider', '$endpoint', $success, $latencyMs)",
        )
    }

    @Test
    fun `recent health counts terminal and failed jobs plus upstream calls in window`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-h", "h@example.com", "H", null)
        // 방금 INSERT → created_at=now, 24h 창 안. terminal = 성공(COMPLETED/READY)+FAILED.
        insertRenderJob(u.id, "COMPLETED")
        insertRenderJob(u.id, "FAILED")
        insertRenderJob(u.id, "PROCESSING")            // 진행중 — terminal 아님
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "FAILED")
        insertSeparationJob(u.id, "QUEUED")            // 진행중 — terminal 아님
        insertCall("perso", "audio-separation", success = true, latencyMs = 100)
        insertCall("perso", "audio-separation", success = true, latencyMs = 300)
        insertCall("perso", "audio-separation", success = false, latencyMs = 500)

        val h = admin.getRecentHealth(24)
        assertEquals(24, h.windowHours)
        assertEquals(4, h.jobsTerminal)   // render COMPLETED+FAILED + sep READY+FAILED
        assertEquals(2, h.jobsFailed)     // render FAILED + sep FAILED
        assertEquals(3, h.upstreamCalls)
        assertEquals(1, h.upstreamFailures)
    }

    // ── getDeletionStats tenure ──────────────────────────────────────────────

    private fun insertDeletion(provider: String, signedUpAt: Instant, deletedAt: Instant) = transaction {
        exec(
            "INSERT INTO account_deletions (provider, signed_up_at, deleted_at) VALUES ('$provider', ?, ?)",
            args = listOf<Pair<IColumnType<*>, Any?>>(
                JavaInstantColumnType() to signedUpAt,
                JavaInstantColumnType() to deletedAt,
            ),
        )
    }

    @Test
    fun `deletion stats compute average and median tenure in days`() {
        val now = Instant.now()
        val day = 86_400L
        // 체류기간 2·4·9일 → avg 5.0, median 4.0.
        insertDeletion("apple", now.minusSeconds(2 * day), now)
        insertDeletion("google", now.minusSeconds(4 * day), now)
        insertDeletion("apple", now.minusSeconds(9 * day), now)

        val stats = admin.getDeletionStats()
        assertEquals(3, stats.totalDeletions)
        assertEquals(3, stats.deletions30d)
        assertEquals(5.0, stats.avgTenureDays)
        assertEquals(4.0, stats.medianTenureDays)
    }

    @Test
    fun `deletion stats are zero when no deletions`() {
        val stats = admin.getDeletionStats()
        assertEquals(0, stats.totalDeletions)
        assertEquals(0.0, stats.avgTenureDays)
        assertEquals(0.0, stats.medianTenureDays)
    }
}
