package com.vibi.bff.service

import com.vibi.bff.db.UserIdentitiesTable
import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AdminActiveJob
import com.vibi.bff.model.AdminAdStats
import com.vibi.bff.model.AdminCreditEvent
import com.vibi.bff.model.AdminDailyStats
import com.vibi.bff.model.AdminUserAccount
import com.vibi.bff.model.AdminDeletionDaily
import com.vibi.bff.model.AdminDeletionStats
import com.vibi.bff.model.AdminDurationBucket
import com.vibi.bff.model.AdminExternalCallDaily
import com.vibi.bff.model.AdminHealth
import com.vibi.bff.model.AdminJobStatusBreakdown
import com.vibi.bff.model.AdminOverview
import com.vibi.bff.model.AdminSignupDaily
import com.vibi.bff.model.AdminUserJob
import com.vibi.bff.model.AdminUserCreditsResponse
import com.vibi.bff.model.AdminUserOverview
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private val INSTANT_T = JavaInstantColumnType()
private val TEXT_T = TextColumnType()
private val INT_T = IntegerColumnType()
private val UUID_T = UUIDColumnType()

private fun instantArg(v: Instant): Pair<IColumnType<*>, Any?> = INSTANT_T to v
private fun textArg(v: String): Pair<IColumnType<*>, Any?> = TEXT_T to v
private fun intArg(v: Int): Pair<IColumnType<*>, Any?> = INT_T to v
private fun uuidArg(v: UUID): Pair<IColumnType<*>, Any?> = UUID_T to v

private fun scalarLong(sql: String, args: List<Pair<IColumnType<*>, Any?>> = emptyList()): Long =
    TransactionManager.current().exec(sql, args = args) { rs ->
        rs.next(); rs.getLong(1)
    } ?: 0L

/** 소수 첫째 자리 반올림 — 체류기간(일) 표시용. */
private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

/** 정렬 후 중앙값. 짝수개면 가운데 두 값의 평균. 빈 리스트는 호출측에서 가드. */
private fun medianOf(values: List<Long>): Double {
    val sorted = values.sorted()
    val n = sorted.size
    val mid = n / 2
    return if (n % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * admin 대시보드용 read-only 쿼리. mutating action 없음 (v1 의도적 제외).
 *
 * 모든 쿼리는 raw SQL — Exposed 의 group-by/aggregation API 로도 가능하나 raw 가 가독성 우위.
 * Postgres + H2 (PostgreSQL mode) 양쪽에서 동일 구문이 동작하는지 확인된 SQL 만 사용.
 */
class AdminRepository(
    /** identity 조립을 사용자 대면 경로와 공유하기 위한 의존성 ([getUserAccount] 만 사용). */
    private val userRepository: UserRepository,
) {

    /**
     * 지정 기간 [fromInclusive, toExclusive) 의 일별 render/separation 카운트 + 누적 입력 길이.
     * 날짜 단위는 UTC. 빈 날짜는 결과에 포함되지 않음 — 호출자가 필요하면 채워서 표시.
     */
    fun getDailyStats(fromInclusive: Instant, toExclusive: Instant): List<AdminDailyStats> = transaction {
        // 별칭은 'bucket_date' — 'day' 는 H2(PostgreSQL mode) 예약어라 AS day 가 깨진다.
        val sql = """
            SELECT
                d::date AS bucket_date,
                COALESCE(r.cnt, 0) AS render_count,
                COALESCE(r.dur, 0) AS render_duration,
                COALESCE(s.cnt, 0) AS separation_count,
                COALESCE(s.plugin_cnt, 0) AS separation_plugin_count
            FROM (
                SELECT generate_series(?::timestamp::date, (?::timestamp - INTERVAL '1 day')::date, INTERVAL '1 day') AS d
            ) days
            LEFT JOIN (
                SELECT date_trunc('day', created_at)::date AS bucket_date, COUNT(*) AS cnt, SUM(source_duration_ms) AS dur
                FROM render_jobs WHERE created_at >= ? AND created_at < ? GROUP BY 1
            ) r ON r.bucket_date = d::date
            LEFT JOIN (
                SELECT date_trunc('day', created_at)::date AS bucket_date, COUNT(*) AS cnt,
                       SUM(CASE WHEN client = 'plugin' THEN 1 ELSE 0 END) AS plugin_cnt
                FROM separation_jobs WHERE created_at >= ? AND created_at < ? GROUP BY 1
            ) s ON s.bucket_date = d::date
            ORDER BY d::date
        """.trimIndent()
        val results = mutableListOf<AdminDailyStats>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
            instantArg(fromInclusive), instantArg(toExclusive),
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                val day = rs.getDate("bucket_date").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val sepTotal = rs.getLong("separation_count")
                val sepPlugin = rs.getLong("separation_plugin_count")
                results += AdminDailyStats(
                    date = day,
                    renderCount = rs.getLong("render_count"),
                    separationCount = sepTotal,
                    mobileSeparationCount = sepTotal - sepPlugin,
                    pluginSeparationCount = sepPlugin,
                    totalSourceDurationMs = rs.getLong("render_duration"),
                )
            }
        }
        results
    }

    /**
     * 사용자 페이지 + 각 사용자의 누적 사용량. 최근 활동 시간 기준 desc 정렬.
     * total 은 같은 트랜잭션에서 count(*) — 페이지 사이 가입자 유입 차이는 v1 무시.
     *
     * [query] non-blank 면 email/name 부분일치 검색 (대소문자 무시). 인터뷰/지원 대응 시 자주 필요.
     *
     * [client] 는 'mobile' | 'plugin' | null. 잡 이력 기준 필터 — 'plugin' 은 plugin 분리 잡
     * 1건 이상, 'mobile' 은 render 또는 mobile 분리 잡 1건 이상인 사용자만. 잡이 아예 없는
     * 가입-only 사용자는 어느 필터에도 안 잡힌다 (전체 보기에서만 노출).
     */
    fun getUsersOverview(
        limit: Int,
        offset: Int,
        query: String?,
        client: String? = null,
    ): Pair<List<AdminUserOverview>, Long> = transaction {
        require(limit in 1..200) { "limit must be in 1..200 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }
        require(client == null || client == "mobile" || client == "plugin") {
            "client must be 'mobile' or 'plugin' (got $client)"
        }

        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        // LIKE 와일드카드(%, _)와 escape 문자(\) 를 모두 escape — 안 하면 'a_b@x.com' 검색이
        // '_' 를 single-char wildcard 로 처리해 'axb@x.com' 같은 오탐을 낸다 (잘못된 계정 row →
        // 오조작 role 변경 위험). backslash 를 먼저 escape 해야 뒤 치환이 중복 escape 되지 않는다.
        // Postgres·H2 모두 LIKE default escape 가 backslash 라 별도 ESCAPE 절 불필요.
        val likePattern = q?.let {
            val escaped = it.lowercase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            "%$escaped%"
        }
        val conditions = mutableListOf<String>()
        if (q != null) conditions += "(LOWER(u.email) LIKE ? OR LOWER(u.name) LIKE ?)"
        when (client) {
            "plugin" -> conditions += "COALESCE(s.plugin_cnt, 0) > 0"
            "mobile" -> conditions += "(COALESCE(r.cnt, 0) > 0 OR COALESCE(s.cnt, 0) - COALESCE(s.plugin_cnt, 0) > 0)"
        }
        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val whereArgs: List<Pair<IColumnType<*>, Any?>> =
            if (likePattern != null) listOf(textArg(likePattern), textArg(likePattern)) else emptyList()

        // client 필터가 잡 aggregate 를 참조하므로 count 도 동일 join 위에서 계산.
        val fromClause = """
            FROM users u
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS cnt, SUM(source_duration_ms) AS dur, MAX(created_at) AS last_at
                FROM render_jobs GROUP BY user_id
            ) r ON r.user_id = u.id
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS cnt,
                       SUM(CASE WHEN client = 'plugin' THEN 1 ELSE 0 END) AS plugin_cnt,
                       MAX(created_at) AS last_at
                FROM separation_jobs GROUP BY user_id
            ) s ON s.user_id = u.id
        """.trimIndent()

        val total: Long = scalarLong("SELECT COUNT(*) $fromClause $whereClause", whereArgs)

        val rows = mutableListOf<AdminUserOverview>()
        // userId → primary provider(users.provider). 아래 secondary(user_identities) 와 합쳐 linkedProviders 구성.
        val primaryProviderById = HashMap<String, String>()
        val sql = """
            SELECT
                u.id, u.email, u.name, u.role, u.created_at, u.provider,
                COALESCE(r.cnt, 0) AS render_count,
                COALESCE(r.dur, 0) AS render_duration,
                COALESCE(r.last_at, NULL) AS render_last,
                COALESCE(s.cnt, 0) AS sep_count,
                COALESCE(s.plugin_cnt, 0) AS sep_plugin_count,
                COALESCE(s.last_at, NULL) AS sep_last
            $fromClause
            $whereClause
            ORDER BY GREATEST(
                COALESCE(r.last_at, u.created_at),
                COALESCE(s.last_at, u.created_at)
            ) DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
        args.addAll(whereArgs)
        args.add(intArg(limit))
        args.add(intArg(offset))
        TransactionManager.current().exec(sql, args = args) { rs ->
            while (rs.next()) {
                val renderLast = rs.getTimestamp("render_last")?.toInstant()
                val sepLast = rs.getTimestamp("sep_last")?.toInstant()
                val created = rs.getTimestamp("created_at").toInstant()
                val lastActivity = listOfNotNull(renderLast, sepLast).maxOrNull() ?: created
                val sepCount = rs.getLong("sep_count")
                val sepPlugin = rs.getLong("sep_plugin_count")
                val userId = (rs.getObject("id") as UUID).toString()
                primaryProviderById[userId] = rs.getString("provider")
                rows += AdminUserOverview(
                    userId = userId,
                    email = rs.getString("email"),
                    name = rs.getString("name"),
                    role = rs.getString("role"),
                    totalRenders = rs.getLong("render_count"),
                    totalSeparations = sepCount,
                    mobileSeparations = sepCount - sepPlugin,
                    pluginSeparations = sepPlugin,
                    totalSourceDurationMs = rs.getLong("render_duration"),
                    lastActivityAt = DateTimeFormatter.ISO_INSTANT.format(lastActivity),
                )
            }
        }

        // 연결된 provider 부착 — 이 페이지 사용자들의 user_identities(secondary) 를 한 번에 조회 후 매핑.
        // (primary=users.provider 는 위에서 캡처.) 통합 안 한 계정은 provider 1개만 나온다.
        val ids = rows.map { UUID.fromString(it.userId) }
        val secondaryByAccount: Map<UUID, List<String>> =
            if (ids.isEmpty()) emptyMap()
            else UserIdentitiesTable
                .select(UserIdentitiesTable.accountId, UserIdentitiesTable.provider)
                .where { UserIdentitiesTable.accountId inList ids }
                .groupBy({ it[UserIdentitiesTable.accountId] }, { it[UserIdentitiesTable.provider] })
        val enriched = rows.map { u ->
            val providers = (
                listOfNotNull(primaryProviderById[u.userId]) +
                    (secondaryByAccount[UUID.fromString(u.userId)] ?: emptyList())
                ).distinct()
            u.copy(linkedProviders = providers)
        }
        enriched to total
    }

    /**
     * 외부 API 호출 일별 추세. provider/endpoint 별 grouping — 비용 예측의 raw 데이터.
     * p95 latency 는 Postgres `percentile_cont` 사용 (H2 는 동일 함수 없어 dev/test 에선 0 fallback).
     */
    fun getExternalCallsDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminExternalCallDaily> = transaction {
        val isPostgres = TransactionManager.current().db.url.startsWith("jdbc:postgresql:")
        val p95Expr = if (isPostgres) {
            "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)::bigint"
        } else {
            "COALESCE(MAX(latency_ms), 0)" // H2 fallback — exact p95 없으므로 max 로 근사
        }
        val sql = """
            SELECT
                date_trunc('day', created_at)::date AS day,
                provider,
                endpoint,
                COUNT(*) AS call_count,
                SUM(CASE WHEN success = false THEN 1 ELSE 0 END) AS failure_count,
                $p95Expr AS p95_latency
            FROM external_api_calls
            WHERE created_at >= ? AND created_at < ?
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
        """.trimIndent()
        val rows = mutableListOf<AdminExternalCallDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminExternalCallDaily(
                    date = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    provider = rs.getString("provider"),
                    endpoint = rs.getString("endpoint"),
                    callCount = rs.getLong("call_count"),
                    failureCount = rs.getLong("failure_count"),
                    p95LatencyMs = rs.getLong("p95_latency"),
                )
            }
        }
        rows
    }

    /**
     * 영상 길이 분포 5 bucket. Perso 영업 미팅에서 자주 묻는 "어떤 길이가 주로 분리되나" 답변.
     * Postgres CASE WHEN 으로 buckets 직접 — 차트가 0 행도 표시할 수 있도록 모든 bucket 반환.
     */
    fun getDurationHistogram(): List<AdminDurationBucket> = transaction {
        val sql = """
            SELECT
                CASE
                    WHEN source_duration_ms < 60000 THEN '0-1m'
                    WHEN source_duration_ms < 300000 THEN '1-5m'
                    WHEN source_duration_ms < 900000 THEN '5-15m'
                    WHEN source_duration_ms < 3600000 THEN '15-60m'
                    ELSE '60m+'
                END AS bucket,
                COUNT(*) AS c
            FROM render_jobs
            GROUP BY 1
        """.trimIndent()
        val counts = mutableMapOf<String, Long>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) counts[rs.getString("bucket")] = rs.getLong("c")
        }
        // 0 count bucket 도 명시적으로 포함해 차트가 일관된 5 칸으로 렌더링.
        listOf("0-1m", "1-5m", "5-15m", "15-60m", "60m+").map { b ->
            AdminDurationBucket(bucket = b, count = counts[b] ?: 0L)
        }
    }

    /**
     * 진행 중 잡 (render + separation, status='PROCESSING'). 서버 재시작 후 orphan 탐지.
     * 가장 오래된 것 먼저 (stuck 의심 가능). 최대 100개 cap.
     */
    fun getActiveJobs(): List<AdminActiveJob> = transaction {
        val sql = """
            SELECT job_type, job_id, email, source_duration_ms, created_at, client FROM (
                SELECT 'render' AS job_type, r.id AS job_id, u.email, r.source_duration_ms, r.created_at,
                       'mobile' AS client
                FROM render_jobs r JOIN users u ON u.id = r.user_id
                WHERE r.status = 'PROCESSING'
                UNION ALL
                SELECT 'separation' AS job_type, s.id AS job_id, u.email, s.source_duration_ms, s.created_at,
                       s.client
                FROM separation_jobs s JOIN users u ON u.id = s.user_id
                WHERE s.status = 'PROCESSING'
            ) t
            ORDER BY created_at ASC
            LIMIT 100
        """.trimIndent()
        val rows = mutableListOf<AdminActiveJob>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                rows += AdminActiveJob(
                    jobType = rs.getString("job_type"),
                    jobId = rs.getString("job_id"),
                    userEmail = rs.getString("email"),
                    sourceDurationMs = rs.getLong("source_duration_ms"),
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("created_at").toInstant()),
                    client = rs.getString("client"),
                )
            }
        }
        rows
    }

    /**
     * 일별 신규 가입자 + provider 분포. iOS-first 정책의 Apple 사용자 비중 검증용.
     */
    fun getSignupDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminSignupDaily> = transaction {
        val sql = """
            SELECT
                date_trunc('day', created_at)::date AS day,
                SUM(CASE WHEN provider = 'google' THEN 1 ELSE 0 END) AS google_count,
                SUM(CASE WHEN provider = 'apple'  THEN 1 ELSE 0 END) AS apple_count
            FROM users
            WHERE created_at >= ? AND created_at < ?
            GROUP BY 1
            ORDER BY 1
        """.trimIndent()
        val rows = mutableListOf<AdminSignupDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminSignupDaily(
                    date = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    googleCount = rs.getLong("google_count"),
                    appleCount = rs.getLong("apple_count"),
                )
            }
        }
        rows
    }

    /**
     * 회원탈퇴 요약 — account_deletions row 집계. 누적 + 최근 30일 + 체류기간(가입~탈퇴).
     *
     * 체류기간 avg/median 은 앱 코드에서 계산 — `EXTRACT(EPOCH FROM interval)` 등이 Postgres/H2
     * 간 방언 차가 커서, 두 timestamp 만 읽어 Duration 으로 산출한다(탈퇴는 저빈도라 전량 스캔 OK).
     * (AdminDeletionStats KDoc 참조)
     */
    fun getDeletionStats(): AdminDeletionStats = transaction {
        val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 3600)
        var total = 0L
        var deletions30d = 0L
        val tenureSeconds = mutableListOf<Long>()
        val sql = "SELECT signed_up_at, deleted_at FROM account_deletions"
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                total++
                val signedUp = rs.getTimestamp("signed_up_at").toInstant()
                val deleted = rs.getTimestamp("deleted_at").toInstant()
                if (!deleted.isBefore(thirtyDaysAgo)) deletions30d++
                tenureSeconds += java.time.Duration.between(signedUp, deleted).seconds.coerceAtLeast(0)
            }
        }
        AdminDeletionStats(
            totalDeletions = total,
            deletions30d = deletions30d,
            avgTenureDays = if (tenureSeconds.isEmpty()) 0.0
            else round1(tenureSeconds.average() / 86_400.0),
            medianTenureDays = if (tenureSeconds.isEmpty()) 0.0
            else round1(medianOf(tenureSeconds) / 86_400.0),
        )
    }

    /**
     * 최근 시간창([windowHours]) 헬스 — Overview 헬스 카드용. 누적이 아닌 rolling window 라
     * 오늘의 급성 실패 스파이크를 잡는다. 창 기준은 created_at(제출 시각) — render/separation 두
     * 테이블에 공통 존재하고 external_api_calls 와도 일관. (AdminHealth KDoc 참조)
     */
    fun getRecentHealth(windowHours: Int): AdminHealth = transaction {
        val since = Instant.now().minusSeconds(windowHours.toLong() * 3600)

        // 잡: render(COMPLETED) + separation(READY) 을 성공으로, FAILED 를 실패로 — 창 내 종료분만.
        fun jobsInWindow(table: String, successStatus: String): Pair<Long, Long> {
            val sql = """
                SELECT
                    SUM(CASE WHEN status IN ('$successStatus', 'FAILED') THEN 1 ELSE 0 END) AS terminal,
                    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM $table
                WHERE created_at >= ?
            """.trimIndent()
            var terminal = 0L
            var failed = 0L
            TransactionManager.current().exec(sql, args = listOf(instantArg(since))) { rs ->
                if (rs.next()) {
                    terminal = rs.getLong("terminal")
                    failed = rs.getLong("failed")
                }
            }
            return terminal to failed
        }
        val (renderTerminal, renderFailed) = jobsInWindow("render_jobs", "COMPLETED")
        val (sepTerminal, sepFailed) = jobsInWindow("separation_jobs", "READY")

        // 외부호출: getExternalCallsDaily 와 동일한 p95 방언 분기.
        val isPostgres = TransactionManager.current().db.url.startsWith("jdbc:postgresql:")
        val p95Expr = if (isPostgres) {
            "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)::bigint"
        } else {
            "COALESCE(MAX(latency_ms), 0)"
        }
        val callSql = """
            SELECT
                COUNT(*) AS calls,
                SUM(CASE WHEN success = false THEN 1 ELSE 0 END) AS failures,
                $p95Expr AS p95
            FROM external_api_calls
            WHERE created_at >= ?
        """.trimIndent()
        var calls = 0L
        var failures = 0L
        var p95 = 0L
        TransactionManager.current().exec(callSql, args = listOf(instantArg(since))) { rs ->
            if (rs.next()) {
                calls = rs.getLong("calls")
                failures = rs.getLong("failures")
                p95 = rs.getLong("p95")
            }
        }

        AdminHealth(
            windowHours = windowHours,
            jobsTerminal = renderTerminal + sepTerminal,
            jobsFailed = renderFailed + sepFailed,
            upstreamCalls = calls,
            upstreamFailures = failures,
            upstreamP95Ms = p95,
        )
    }

    /**
     * 일별 탈퇴 수 + provider 분포. 가입 추이(getSignupDaily) 대비 이탈 비교용 — 동일 구조.
     */
    fun getDeletionDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminDeletionDaily> = transaction {
        val sql = """
            SELECT
                date_trunc('day', deleted_at)::date AS day,
                SUM(CASE WHEN provider = 'google' THEN 1 ELSE 0 END) AS google_count,
                SUM(CASE WHEN provider = 'apple'  THEN 1 ELSE 0 END) AS apple_count
            FROM account_deletions
            WHERE deleted_at >= ? AND deleted_at < ?
            GROUP BY 1
            ORDER BY 1
        """.trimIndent()
        val rows = mutableListOf<AdminDeletionDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminDeletionDaily(
                    date = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    googleCount = rs.getLong("google_count"),
                    appleCount = rs.getLong("apple_count"),
                )
            }
        }
        rows
    }

    /**
     * 특정 사용자의 render 잡 페이지 + 각 잡의 separation 횟수. 최신순.
     */
    fun getUserJobs(userId: UUID, limit: Int, offset: Int): Pair<List<AdminUserJob>, Long> = transaction {
        require(limit in 1..200) { "limit must be in 1..200 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }

        val total: Long = scalarLong(
            "SELECT COUNT(*) FROM render_jobs WHERE user_id = ?",
            listOf(uuidArg(userId)),
        )

        val rows = mutableListOf<AdminUserJob>()
        val sql = """
            SELECT
                r.id, r.status, r.source_duration_ms, r.created_at, r.finished_at,
                COALESCE(s.cnt, 0) AS separation_count
            FROM render_jobs r
            LEFT JOIN (
                SELECT render_job_id, COUNT(*) AS cnt FROM separation_jobs
                WHERE render_job_id IS NOT NULL GROUP BY render_job_id
            ) s ON s.render_job_id = r.id
            WHERE r.user_id = ?
            ORDER BY r.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        TransactionManager.current().exec(sql, args = listOf(
            uuidArg(userId), intArg(limit), intArg(offset),
        )) { rs ->
            while (rs.next()) {
                rows += AdminUserJob(
                    jobId = rs.getString("id"),
                    status = rs.getString("status"),
                    sourceDurationMs = rs.getLong("source_duration_ms"),
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("created_at").toInstant()),
                    finishedAt = rs.getTimestamp("finished_at")?.toInstant()?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
                    separationCount = rs.getLong("separation_count"),
                )
            }
        }
        rows to total
    }

    /**
     * 사용자 상세 페이지용 — 계정에 연결된 로그인 수단.
     *
     * [UserRepository.listIdentities] 재사용 (users primary + user_identities secondary). 사용자 대면
     * GET /auth/identities 와 단일 소스라 조립 로직이 어긋나지 않는다. 없는 userId 는 빈 리스트.
     *
     * 병합 이력은 여기서 반환하지 않는다 — 같은 account_merges row 를 [getUserCredits] 의
     * 타임라인이 'merge_carry' 이벤트로 이미 보여준다 (표시 정본 1곳).
     */
    fun getUserAccount(userId: UUID): AdminUserAccount =
        transaction { AdminUserAccount(identities = userRepository.listIdentities(userId)) }

    /**
     * 사용자 상세 페이지용 — 크레딧 변동 타임라인 (최신순, 페이지네이션).
     *
     * 세 소스를 `UNION ALL` 한 뒤 정렬·페이징한다. 소스별로 나눠 조회한 뒤 코틀린에서 합치면
     * 페이지 경계가 어긋나므로(각각 limit 을 적용하게 됨) 한 쿼리로 뽑는다:
     *   • credit_transactions — purchase / ad_reward(admob) / admin_grant(admin)
     *   • credit_ledger       — signup / separation(consume, 음수) / refund
     *   • account_merges      — merge_carry (이월 크레딧)
     *
     * consume/refund 의 ref_id 는 "consume-<jobId>" / "refund-<jobId>" 라 접두사를 떼 잡 ID 를
     * 얻고, 페이지가 확정된 뒤 그 ID 들만 separation_jobs(TEXT PK) 에서 조회해 입력 길이를 붙인다
     * — "몇 분짜리 분리로 몇 개 차감" 표시용. 잡 row 가 이미 없으면 길이만 null 이고 이벤트는 남는다.
     *
     * 반환 [AdminUserCreditsResponse.balance] 와 이벤트 delta 합계가 어긋날 수 있는 이유는
     * 해당 DTO 의 KDoc 참조 (계정 병합의 re-point). hasMerges 로 UI 가 경고를 띄운다.
     */
    fun getUserCredits(userId: UUID, limit: Int, offset: Int): AdminUserCreditsResponse = transaction {
        require(limit in 1..200) { "limit must be in 1..200 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }

        // user_credits row 가 없는 사용자(보너스 지급 전/이력 없음)도 0 으로 떨어지도록 스칼라
        // 서브쿼리 + COALESCE — scalarLong 은 결과 row 가 없으면 JDBC 레벨에서 터진다.
        val balance = scalarLong(
            "SELECT COALESCE((SELECT balance FROM user_credits WHERE user_id = ?), 0)",
            listOf(uuidArg(userId)),
        ).toInt()
        // 전체 건수 + 병합 유무를 한 번에 (세 소스를 각각 COUNT 하면 왕복만 늘어난다).
        var total = 0L
        var mergeCount = 0L
        val countSql = """
            SELECT (SELECT COUNT(*) FROM credit_transactions WHERE user_id = ?) AS tx_count,
                   (SELECT COUNT(*) FROM credit_ledger WHERE user_id = ?) AS ledger_count,
                   (SELECT COUNT(*) FROM account_merges WHERE into_account_id = ?) AS merge_count
        """.trimIndent()
        TransactionManager.current().exec(countSql, args = listOf(
            uuidArg(userId), uuidArg(userId), uuidArg(userId),
        )) { rs ->
            if (rs.next()) {
                mergeCount = rs.getLong("merge_count")
                total = rs.getLong("tx_count") + rs.getLong("ledger_count") + mergeCount
            }
        }

        // 'day' 처럼 예약어 충돌을 피하려 별칭은 at/ev_type 사용. NULL 컬럼은 UNION 의 타입
        // 결정을 위해 CAST 필수 (H2 는 캐스트 없는 NULL 컬럼의 UNION 을 거부).
        //
        // ev_id 는 "<소스>:<PK>" 로 전역 유니크 — 같은 시각에 여러 이벤트가 몰려도 정렬이
        // 결정적이라 페이지 경계에서 행이 중복/누락되지 않는다. 클라이언트의 append 중복 제거
        // 키로도 쓰인다 (offset 페이징 중 새 이벤트가 생겨 경계가 밀리는 경우 방어).
        val sql = """
            SELECT ev_id, at, ev_type, delta, detail, job_id, source_duration_ms
            FROM (
                SELECT 'tx:' || CAST(ct.id AS VARCHAR) AS ev_id,
                       ct.created_at AS at,
                       CASE ct.platform
                           WHEN 'admob' THEN 'ad_reward'
                           WHEN 'admin' THEN 'admin_grant'
                           ELSE 'purchase'
                       END AS ev_type,
                       ct.credits AS delta,
                       ct.product_id AS detail,
                       CAST(NULL AS VARCHAR) AS job_id,
                       CAST(NULL AS BIGINT) AS source_duration_ms
                FROM credit_transactions ct
                WHERE ct.user_id = ?
                UNION ALL
                SELECT 'ledger:' || CAST(cl.id AS VARCHAR),
                       cl.created_at,
                       CASE cl.kind WHEN 'consume' THEN 'separation' ELSE cl.kind END,
                       CASE WHEN cl.kind = 'consume' THEN -cl.credits ELSE cl.credits END,
                       CAST(NULL AS VARCHAR),
                       CASE cl.kind
                           WHEN 'consume' THEN SUBSTRING(cl.ref_id, 9)
                           WHEN 'refund' THEN SUBSTRING(cl.ref_id, 8)
                           ELSE NULL
                       END,
                       CAST(NULL AS BIGINT)
                FROM credit_ledger cl
                WHERE cl.user_id = ?
                UNION ALL
                SELECT 'merge:' || CAST(am.id AS VARCHAR),
                       am.merged_at,
                       'merge_carry',
                       am.carried_credits,
                       am.from_provider || ':' || am.from_email,
                       CAST(NULL AS VARCHAR),
                       CAST(NULL AS BIGINT)
                FROM account_merges am
                WHERE am.into_account_id = ?
            ) e
            ORDER BY at DESC, ev_id DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        val events = mutableListOf<AdminCreditEvent>()
        TransactionManager.current().exec(sql, args = listOf(
            uuidArg(userId), uuidArg(userId), uuidArg(userId), intArg(limit), intArg(offset),
        )) { rs ->
            while (rs.next()) {
                val durationMs = rs.getLong("source_duration_ms").takeIf { !rs.wasNull() }
                events += AdminCreditEvent(
                    id = rs.getString("ev_id"),
                    at = DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("at").toInstant()),
                    type = rs.getString("ev_type"),
                    delta = rs.getInt("delta"),
                    detail = rs.getString("detail"),
                    jobId = rs.getString("job_id"),
                    sourceDurationMs = durationMs,
                )
            }
        }

        // 입력 길이는 페이지에 실제로 뜬 잡(<= limit 건)만 PK 조회로 보강한다. UNION 안에서
        // separation_jobs 를 LEFT JOIN 하면 LIMIT 전에 조인이 끝나야 해서, Postgres 가
        // separation_jobs 전체를 해시로 올리고 사용자의 ledger 전 구간을 조인한다 (테이블이
        // 커질수록 페이지 1장 여는 비용이 같이 커짐). 조인을 밖으로 빼 두 단계 모두 bounded.
        val jobIds = events.mapNotNull { it.jobId }.distinct()
        if (jobIds.isNotEmpty()) {
            val durations = HashMap<String, Long>(jobIds.size)
            val placeholders = jobIds.joinToString(", ") { "?" }
            TransactionManager.current().exec(
                "SELECT id, source_duration_ms FROM separation_jobs WHERE id IN ($placeholders)",
                args = jobIds.map { textArg(it) },
            ) { rs ->
                while (rs.next()) durations[rs.getString("id")] = rs.getLong("source_duration_ms")
            }
            events.replaceAll { e ->
                val ms = e.jobId?.let { durations[it] }
                if (ms == null) e else e.copy(sourceDurationMs = ms)
            }
        }

        AdminUserCreditsResponse(
            balance = balance,
            events = events,
            total = total,
            hasMerges = mergeCount > 0,
        )
    }

    /**
     * 보상형 광고(AdMob) 시청 요약. credit_transactions 의 platform='admob' row 를 집계 —
     * 1 row = 광고 1회 시청 완료(= SSV 콜백 1건, 1 크레딧). (AdminAdStats KDoc 참조)
     */
    fun getAdStats(): AdminAdStats = transaction {
        val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 3600)
        val sql = """
            SELECT
                COUNT(*) AS total_watches,
                COALESCE(SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END), 0) AS watches_30d,
                COUNT(DISTINCT user_id) AS watching_users
            FROM credit_transactions
            WHERE platform = 'admob'
        """.trimIndent()
        var stats = AdminAdStats(0, 0, 0)
        TransactionManager.current().exec(sql, args = listOf(instantArg(thirtyDaysAgo))) { rs ->
            if (rs.next()) {
                stats = AdminAdStats(
                    totalWatches = rs.getLong("total_watches"),
                    watches30d = rs.getLong("watches_30d"),
                    watchingUsers = rs.getLong("watching_users"),
                )
            }
        }
        stats
    }

    /**
     * [userId] 의 role 을 [role] ('admin' | 'user') 로 변경. 갱신된 row 수(0 또는 1) 반환 —
     * 존재하지 않는 userId 면 0.
     *
     * 주의 — JWT 는 발급 시점 role 클레임을 그대로 쓰므로 (Auth.kt 참조) 승격/강등은 대상
     * 사용자가 재로그인해 새 토큰을 받기 전까지 반영되지 않는다. 즉시 강등으로 세션을 끊는
     * 용도로는 부적합.
     *
     * 예외로 raw SQL 대신 Exposed DSL 사용 — update count 를 명시적으로 돌려받기 위함.
     */
    fun setUserRole(userId: UUID, role: String): Int = transaction {
        require(role == "admin" || role == "user") { "role must be 'admin' or 'user' (got $role)" }
        UsersTable.update({ UsersTable.id eq userId }) {
            it[UsersTable.role] = role
            it[UsersTable.updatedAt] = Instant.now()
        }
    }

    /**
     * render/separation 잡의 성공/실패/진행중 분해. 성공 status 가 종류별로 다름:
     * render=COMPLETED, separation=READY. 실패는 둘 다 FAILED, 나머지는 inProgress.
     * separation 은 클라이언트('mobile'/'plugin') 행으로 분리 — render 는 모바일 전용이라 단일 행.
     */
    fun getJobStatusBreakdown(): List<AdminJobStatusBreakdown> = transaction {
        fun breakdown(
            table: String,
            successStatus: String,
            client: String? = null,
        ): AdminJobStatusBreakdown {
            val clientFilter = if (client != null) "WHERE client = ?" else ""
            val sql = """
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN status = '$successStatus' THEN 1 ELSE 0 END) AS succeeded,
                    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM $table
                $clientFilter
            """.trimIndent()
            val args = if (client != null) listOf(textArg(client)) else emptyList()
            var total = 0L
            var succeeded = 0L
            var failed = 0L
            TransactionManager.current().exec(sql, args = args) { rs ->
                if (rs.next()) {
                    total = rs.getLong("total")
                    succeeded = rs.getLong("succeeded")
                    failed = rs.getLong("failed")
                }
            }
            return AdminJobStatusBreakdown(
                jobType = if (table == "render_jobs") "render" else "separation",
                client = client,
                total = total,
                succeeded = succeeded,
                failed = failed,
                inProgress = (total - succeeded - failed).coerceAtLeast(0),
            )
        }
        // 잡 종류 식별자는 'render'/'separation' 고정 — 테이블명에서 파생.
        listOf(
            breakdown("render_jobs", "COMPLETED"),
            breakdown("separation_jobs", "READY", client = "mobile"),
            breakdown("separation_jobs", "READY", client = "plugin"),
        )
    }

    /**
     * 대시보드 상단 KPI 카드 4종. 단일 쿼리 묶음.
     */
    fun getOverview(): AdminOverview = transaction {
        val sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 3600)
        val totalSeparations = scalarLong("SELECT COUNT(*) FROM separation_jobs")
        val pluginSeparations = scalarLong("SELECT COUNT(*) FROM separation_jobs WHERE client = 'plugin'")
        AdminOverview(
            totalUsers = scalarLong("SELECT COUNT(*) FROM users"),
            totalRenders = scalarLong("SELECT COUNT(*) FROM render_jobs"),
            totalSeparations = totalSeparations,
            mobileSeparations = totalSeparations - pluginSeparations,
            pluginSeparations = pluginSeparations,
            totalSourceDurationMs = scalarLong("SELECT COALESCE(SUM(source_duration_ms), 0) FROM render_jobs"),
            totalUserCredits = scalarLong("SELECT COALESCE(SUM(balance), 0) FROM user_credits"),
            // AVG 는 numeric/double 반환 — getLong 이 소수부 절삭 (ms 표시엔 충분). 잡 0건이면 COALESCE 로 0.
            avgSeparationDurationMs = scalarLong("SELECT COALESCE(AVG(source_duration_ms), 0) FROM separation_jobs"),
            avgMobileSeparationDurationMs = scalarLong(
                "SELECT COALESCE(AVG(source_duration_ms), 0) FROM separation_jobs WHERE client <> 'plugin'",
            ),
            avgPluginSeparationDurationMs = scalarLong(
                "SELECT COALESCE(AVG(source_duration_ms), 0) FROM separation_jobs WHERE client = 'plugin'",
            ),
            activeUsersLast7Days = scalarLong(
                """
                    SELECT COUNT(DISTINCT user_id) FROM (
                        SELECT user_id FROM render_jobs WHERE created_at >= ?
                        UNION ALL
                        SELECT user_id FROM separation_jobs WHERE created_at >= ?
                    ) t
                """.trimIndent(),
                listOf(instantArg(sevenDaysAgo), instantArg(sevenDaysAgo)),
            ),
        )
    }
}
