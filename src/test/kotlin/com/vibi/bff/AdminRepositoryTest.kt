package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.SIGNUP_BONUS_CREDITS
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        admin = AdminRepository(users)
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

    // ── getUserAccount (연결된 로그인 수단) ──────────────────────────────────

    @Test
    fun `user account lists the single identity of a solo account`() {
        val solo = users.upsert(AuthProvider.GOOGLE, "g-solo", "solo@example.com", "Solo", null)

        val account = admin.getUserAccount(solo.id)
        assertEquals(1, account.identities.size)
        assertEquals("google", account.identities.single().provider)
        assertEquals("solo@example.com", account.identities.single().email)
        assertEquals(true, account.identities.single().primary)
    }

    @Test
    fun `merge does not re-point absorbed admob credits into the surviving account daily cap`() {
        // 회귀 가드(#2): B 의 admob(보상형 광고) 획득분이 A 로 re-point 되면 A 의 일일 광고 상한에
        // B 의 시청분이 잘못 합산돼 A 가 안 봤는데 cap_reached 로 막힌다. 병합은 admob row 를 제외해야.
        allowAdminPlatformInH2()
        val a = users.upsert(AuthProvider.GOOGLE, "g-a", "a@example.com", "Alice", null)
        val b = users.upsert(AuthProvider.APPLE, "ap-b", "b@icloud.com", "Bob", null)
        val now = Instant.now()
        insertTxn(b.id, "admob", "ad-1", 1, now) // B 가 오늘 광고 1회 시청
        insertTxn(b.id, "google", "buy-1", 50, now) // B 의 결제분 — 이건 A 로 re-point 되어야

        users.linkOrMerge(a.id, AuthProvider.APPLE, "ap-b", "b@icloud.com", "Bob", null)

        val since = now.minus(24, java.time.temporal.ChronoUnit.HOURS)
        // admob 은 A 로 안 넘어옴 (B 삭제로 user_id NULL 익명화) → A 의 일일 광고 합 0.
        assertEquals(0, credits.admobGrantedCreditsSince(a.id, since))
        // 결제분(google)은 A 로 re-point 되어 감사 보존.
        val buyOwner = transaction {
            exec("SELECT user_id FROM credit_transactions WHERE transaction_id = 'buy-1'") { rs ->
                rs.next(); rs.getObject(1) as UUID
            }
        }
        assertEquals(a.id, buyOwner)
    }

    @Test
    fun `user account lists both identities after a merge`() {
        // 병합 이력 자체(흡수된 계정·이월 크레딧)는 크레딧 타임라인의 merge_carry 가 정본 —
        // 여기서는 흡수 후 identity 가 둘 다 붙는지만 본다.
        val a = users.upsert(AuthProvider.GOOGLE, "g-a", "a@example.com", "Alice", null)
        val b = users.upsert(AuthProvider.APPLE, "ap-b", "b@icloud.com", "Bob", null)
        credits.grantSignupBonus(b.id)

        users.linkOrMerge(a.id, AuthProvider.APPLE, "ap-b", "b@icloud.com", "Bob", null)

        val account = admin.getUserAccount(a.id)
        assertEquals(setOf("google", "apple"), account.identities.map { it.provider }.toSet())
    }

    @Test
    fun `users overview search treats underscore as a literal not a wildcard`() {
        // 회귀 가드(#5): '_' 를 escape 안 하면 LIKE 가 single-char wildcard 로 처리해 오탐.
        val exact = users.upsert(AuthProvider.GOOGLE, "g-1", "a_b@example.com", "Exact", null)
        users.upsert(AuthProvider.GOOGLE, "g-2", "axb@example.com", "Decoy", null)

        val (rows, _) = admin.getUsersOverview(50, 0, "a_b@example.com")
        val emails = rows.map { it.email }.toSet()
        assertEquals(setOf("a_b@example.com"), emails)
        assertEquals(exact.id.toString(), rows.single().userId)
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

    // ── getUserCredits (크레딧 변동 타임라인) ─────────────────────────────────

    /** [insertSeparationJob] 과 달리 생성한 잡 ID 를 돌려준다 — reserve/refund 의 ref_id 조립용. */
    private fun insertSeparationJobReturningId(userId: UUID, durationMs: Long): String {
        val id = "sep-" + UUID.randomUUID()
        transaction {
            exec(
                "INSERT INTO separation_jobs (id, user_id, source_duration_ms, status, client) " +
                    "VALUES ('$id', CAST('$userId' AS UUID), $durationMs, 'READY', 'mobile')",
            )
        }
        return id
    }

    @Test
    fun `getUserCredits merges grants consumption and refunds into one timeline`() {
        allowAdminPlatformInH2()
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id)
        credits.grantPurchase(u.id, "google", "tx-1", "vibi.credits.10", 10)
        // 광고/관리자 지급은 platform 만 다른 같은 테이블 — 잔액엔 영향 없이 이벤트 종류만 확인.
        insertTxn(u.id, "admob", "ssv-1", 1, Instant.now())
        insertTxn(u.id, "admin", "grant-1", 7, Instant.now())
        val jobId = insertSeparationJobReturningId(u.id, 252_000)
        credits.reserve(u.id, jobId, 5)

        val res = admin.getUserCredits(u.id, 50, 0)

        assertEquals(5, res.events.size)
        assertEquals(5L, res.total)
        assertEquals(SIGNUP_BONUS_CREDITS + 10 - 5, res.balance)
        assertTrue(!res.hasMerges)

        val byType = res.events.associateBy { it.type }
        assertEquals(SIGNUP_BONUS_CREDITS, byType.getValue("signup").delta)
        assertEquals(10, byType.getValue("purchase").delta)
        assertEquals(1, byType.getValue("ad_reward").delta)
        assertEquals(7, byType.getValue("admin_grant").delta)

        // 분리 차감만 음수 + 잡 ID/입력 길이가 붙어 "몇 분짜리로 몇 개 차감" 을 표시할 수 있다.
        val consumed = byType.getValue("separation")
        assertEquals(-5, consumed.delta)
        assertEquals(jobId, consumed.jobId)
        assertEquals(252_000L, consumed.sourceDurationMs)
        // 결제 이벤트엔 product_id 가, 차감엔 detail 이 없다.
        assertEquals("vibi.credits.10", byType.getValue("purchase").detail)
        assertNull(consumed.detail)

        // 잡 실패 환불도 같은 잡을 가리키는 별도 이벤트로 뜬다.
        credits.refund(jobId)
        val refunded = admin.getUserCredits(u.id, 50, 0).events.single { it.type == "refund" }
        assertEquals(5, refunded.delta)
        assertEquals(jobId, refunded.jobId)
        assertEquals(252_000L, refunded.sourceDurationMs)
    }

    @Test
    fun `getUserCredits keeps the event when the referenced separation job is gone`() {
        // 잡 row 가 사라져도(오래된 잡 정리 등) 차감 이벤트 자체는 남아야 한다 — 잡 ID 는 ledger 의
        // ref_id 에서 나오므로 그대로 뜨고, 길이만 null.
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id)
        credits.reserve(u.id, "sep-ghost", 1)

        val event = admin.getUserCredits(u.id, 50, 0).events.single { it.type == "separation" }
        assertEquals("sep-ghost", event.jobId)
        assertNull(event.sourceDurationMs)
    }

    @Test
    fun `getUserCredits paginates newest first and reports the full total`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val now = Instant.now()
        // 시각을 명시 제어해 정렬을 결정적으로 — 같은 밀리초에 몰리면 순서 단정이 불안정하다.
        insertTxn(u.id, "google", "tx-old", 1, now.minusSeconds(300))
        insertTxn(u.id, "google", "tx-mid", 2, now.minusSeconds(200))
        insertTxn(u.id, "google", "tx-new", 3, now.minusSeconds(100))

        val first = admin.getUserCredits(u.id, 2, 0)
        assertEquals(3L, first.total)
        assertEquals(listOf(3, 2), first.events.map { it.delta })
        // 페이지 경계가 안정적이려면 이벤트 키가 소스를 가로질러 유니크해야 한다.
        assertEquals(2, first.events.map { it.id }.toSet().size)

        val second = admin.getUserCredits(u.id, 2, 2)
        assertEquals(3L, second.total)
        assertEquals(listOf(1), second.events.map { it.delta })
        assertTrue(first.events.none { f -> second.events.any { it.id == f.id } }) // 페이지 간 중복 없음
    }

    @Test
    fun `getUserCredits surfaces merge carry and flags that the sum may not match the balance`() {
        val a = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id)
        val b = users.upsert(AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        credits.grantSignupBonus(b.id)
        credits.grantPurchase(b.id, "google", "earn-1", "vibi.credits.5", 5)
        users.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)

        val res = admin.getUserCredits(a.id, 50, 0)

        assertTrue(res.hasMerges)
        val carry = res.events.single { it.type == "merge_carry" }
        assertEquals(5, carry.delta) // 무료 보너스 제외, 획득분만 이월
        assertEquals("apple:a@icloud.com", carry.detail)

        // B 의 결제 row 가 감사 보존을 위해 A 로 re-point 되므로 타임라인엔 구매(+5)와 이월(+5)이
        // 둘 다 보이지만 실제 잔액 증가는 carry 5 뿐 — hasMerges 가 이 괴리를 UI 에 알린다.
        assertEquals(SIGNUP_BONUS_CREDITS + 5, res.balance)
        assertTrue(res.events.sumOf { it.delta } > res.balance)
    }

    // ── 감사 로그 ────────────────────────────────────────────────────────────

    @Test
    fun `listAudit returns newest first across action types`() {
        val actor = users.upsert(AuthProvider.GOOGLE, "g-admin", "ops@example.com", "Ops", null)
        val target = users.upsert(AuthProvider.GOOGLE, "g-user", "u@example.com", "User", null)

        admin.setUserRoleAudited(target.id, actor.id, "admin")
        // 대상이 사용자 row 가 아닌 액션 — target 은 비고 detail 에 해시만 남는다.
        admin.recordAudit(actor.id, "unblock_rejoin", detail = "a".repeat(64))

        val res = admin.listAudit(50, 0)
        assertEquals(2, res.total)
        assertEquals(listOf("unblock_rejoin", "set_role"), res.entries.map { it.action })
        val unblock = res.entries.first()
        assertNull(unblock.targetUserId)
        assertNull(unblock.targetEmail)
        assertNull(unblock.amount)
    }
}
