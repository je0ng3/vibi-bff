package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * 일별 사용량 집계. `date` 는 UTC 기준 ISO-8601 (YYYY-MM-DD). render/separation 잡 카운트 +
 * 누적 입력 길이 ms — 대시보드의 라인/막대 차트 데이터.
 *
 * separation 은 클라이언트별로 분리 ([mobileSeparationCount] + [pluginSeparationCount] =
 * [separationCount]). render 는 모바일 전용이라 분리 없음.
 */
@Serializable
data class AdminDailyStats(
    val date: String,
    val renderCount: Long,
    val separationCount: Long,
    val mobileSeparationCount: Long = 0,
    val pluginSeparationCount: Long = 0,
    val totalSourceDurationMs: Long,
)

/**
 * 운영자 대시보드의 사용자 목록 한 행. 회원가입 정보 + 누적 사용량.
 *
 * - [totalRenders] / [totalSeparations] — 잡 status 와 무관한 시도 횟수 (FAILED 포함).
 *   대시보드는 "사용 시도" 가 핵심이라 성공만 카운트하지 않는다.
 * - [mobileSeparations] / [pluginSeparations] — 분리 잡의 클라이언트별 분해 (합 = totalSeparations).
 * - [totalSourceDurationMs] — 사용자가 올린 입력 영상의 누적 분량 ms (render_jobs 만).
 * - [lastActivityAt] — 가장 최근 잡 (render or separation) 의 created_at, 둘 다 없으면 가입 시각.
 */
@Serializable
data class AdminUserOverview(
    val userId: String,
    val email: String,
    val name: String,
    val role: String,
    val totalRenders: Long,
    val totalSeparations: Long,
    val mobileSeparations: Long = 0,
    val pluginSeparations: Long = 0,
    val totalSourceDurationMs: Long,
    val lastActivityAt: String,
    /** 이 계정에 연결된 로그인 provider 목록 (primary 먼저 + 링크된 secondary). 통합 안 한 계정은 1개. */
    val linkedProviders: List<String> = emptyList(),
)

@Serializable
data class AdminUsersResponse(
    val users: List<AdminUserOverview>,
    val total: Long,
)

/**
 * 사용자별 render 잡 + 그 잡의 분리 횟수. "영상 당 음원분리 개수" 의 가시화.
 */
@Serializable
data class AdminUserJob(
    val jobId: String,
    val status: String,
    val sourceDurationMs: Long,
    val createdAt: String,
    val finishedAt: String?,
    val separationCount: Long,
)

@Serializable
data class AdminUserJobsResponse(
    val jobs: List<AdminUserJob>,
    val total: Long,
)

/**
 * 사용자 상세 페이지에 표시할 계정 연결 정보.
 *
 * [identities] — 이 계정에 연결된 로그인 수단 전체 (primary=최초 가입 provider + 링크된 secondary).
 * 통합 안 한 계정은 1개. 사용자 대면 GET /auth/identities 와 동일한 [LinkedIdentity] 를 재사용해
 * (provider/email/primary) 조립 로직·DTO 가 한 곳([UserRepository.listIdentities])에만 있도록 한다.
 *
 * 병합 이력은 [AdminUserCreditsResponse] 타임라인의 'merge_carry' 이벤트가 정본 — 같은
 * account_merges row 를 두 화면이 각각 렌더하지 않도록 여기서는 노출하지 않는다.
 */
@Serializable
data class AdminUserAccount(
    val identities: List<LinkedIdentity>,
)

/**
 * 사용자 상세 페이지의 크레딧 변동 타임라인 한 건. 세 소스(credit_transactions / credit_ledger /
 * account_merges)를 하나의 이벤트 스트림으로 합친 것 — 저장 형태가 아니라 표시용 view 다.
 *
 * - [id] — "<소스>:<PK>" 형태의 전역 유니크 키 (tx:12 / ledger:34 / merge:5). 정렬 tiebreaker 이자
 *   클라이언트의 목록 key·중복 제거 키.
 * - [type] — 'signup'(가입 보너스) / 'purchase'(인앱결제) / 'ad_reward'(보상형 광고) /
 *   'admin_grant'(관리자 지급) / 'separation'(음원분리 차감) / 'refund'(분리 실패 환불) /
 *   'merge_carry'(계정 병합 이월).
 * - [delta] — 부호 포함 변동량. 차감(separation)만 음수, 나머지는 양수 (병합 이월은 0 일 수 있다).
 * - [detail] — 타입별 보조 표시값. purchase/ad_reward/admin_grant 는 product_id,
 *   merge_carry 는 "provider:email" (흡수된 계정). 없으면 null.
 * - [jobId] / [sourceDurationMs] — separation·refund 에서 해당 분리 잡과 입력 길이.
 *   잡 row 가 이미 사라졌거나(탈퇴 익명화) 다른 타입이면 null.
 */
@Serializable
data class AdminCreditEvent(
    val id: String,
    val at: String,
    val type: String,
    val delta: Int,
    val detail: String? = null,
    val jobId: String? = null,
    val sourceDurationMs: Long? = null,
)

/**
 * 사용자 크레딧 타임라인 응답.
 *
 * [balance] 는 user_credits 의 실제 잔액이고, [events] 의 delta 합계와 **일치하지 않을 수 있다** —
 * 계정 병합이 있었던 경우 흡수된 계정 B 의 결제 이력(credit_transactions)은 감사 보존을 위해 A 로
 * re-point 되지만 A 의 잔액에 더해진 건 carry(min(B.잔액, B.획득분)) 뿐이고, B 의 소비 이력
 * (credit_ledger)은 의도적으로 orphan 되기 때문이다 (UserRepository.mergeAccounts 참조).
 * re-point 된 row 를 A 자신의 결제와 구분할 컬럼이 없어 소급 보정도 불가능하다. 따라서 UI 는
 * 잔액을 [balance] 로만 표시하고, [hasMerges] 가 true 면 "합계와 잔액이 다를 수 있음" 을 알린다.
 */
@Serializable
data class AdminUserCreditsResponse(
    val balance: Int,
    val events: List<AdminCreditEvent>,
    val total: Long,
    val hasMerges: Boolean,
)

/**
 * 대시보드 상단 KPI 카드. 전체 누적 + 최근 7일 비교 같은 단일 숫자 시리즈.
 * separation 은 클라이언트별 분해 포함 (mobile + plugin = total). render 는 모바일 전용.
 */
@Serializable
data class AdminOverview(
    val totalUsers: Long,
    val totalRenders: Long,
    val totalSeparations: Long,
    val mobileSeparations: Long = 0,
    val pluginSeparations: Long = 0,
    val totalSourceDurationMs: Long,
    val activeUsersLast7Days: Long,
    // 전체 사용자가 현재 보유한 크레딧 잔액 합계 (user_credits.balance 의 SUM). 소비 후 잔액 기준.
    val totalUserCredits: Long = 0,
    // Perso 계정(space)의 남은 quota = 잔여 크레딧 (XP-API-KEY 기준). DB 아닌 외부 호출이라
    // 라우트에서 best-effort 로 채운다 — Perso 조회 실패 시 null (대시보드는 정상 표시).
    val persoAccountCredits: Long? = null,
    // 음원분리 잡 1건당 평균 입력 길이 ms. 전체 + 클라이언트별 (mobile/plugin). 잡이 없으면 0.
    val avgSeparationDurationMs: Long = 0,
    val avgMobileSeparationDurationMs: Long = 0,
    val avgPluginSeparationDurationMs: Long = 0,
)

/**
 * 외부 API (Perso 등) 일별 호출 통계. 비용 추정 + 실패율 가시화.
 *
 * - [provider] — 'perso' 등
 * - [endpoint] — 'audio-separation' 등 logical operation 단위
 * - [callCount] — 성공/실패 합산
 * - [failureCount] — `success=false` 카운트. 실패율 = failureCount / callCount
 * - [p95LatencyMs] — 응답 시간 분포 (Postgres `percentile_cont` 사용)
 */
@Serializable
data class AdminExternalCallDaily(
    val date: String,
    val provider: String,
    val endpoint: String,
    val callCount: Long,
    val failureCount: Long,
    val p95LatencyMs: Long,
)

/**
 * 영상 길이 분포 히스토그램. render_jobs.source_duration_ms 를 5개 bucket 으로 묶음.
 * Perso 영업 미팅에서 자주 묻는 "어떤 길이가 주로 분리되는가" 질문 답변.
 */
@Serializable
data class AdminDurationBucket(
    val bucket: String,          // '0-1m' / '1-5m' / '5-15m' / '15-60m' / '60m+'
    val count: Long,
)

/**
 * 진행 중 잡 — render+separation 통합 PROCESSING 목록. stuck 잡 탐지용.
 * v1 은 DB row 의 status='PROCESSING' 기준 — in-memory map 과 정확히 동기화되진 않지만
 * 서버 재시작 후 orphan 탐지엔 충분.
 */
@Serializable
data class AdminActiveJob(
    val jobType: String,         // 'render' / 'separation'
    val jobId: String,
    val userEmail: String,
    val sourceDurationMs: Long,
    val createdAt: String,
    // 제출 클라이언트 — separation 은 'mobile'/'plugin', render 는 모바일 전용이라 'mobile' 고정.
    val client: String = "mobile",
)

/**
 * 신규 가입자 일별 추세 + provider 분포. iOS-first 정책 검증용.
 */
@Serializable
data class AdminSignupDaily(
    val date: String,
    val googleCount: Long,
    val appleCount: Long,
)

/**
 * 보상형 광고(AdMob) 시청 요약. 보상형 광고 1회 시청 완료 = SSV 콜백 1건 =
 * credit_transactions 의 platform='admob' row 1건 (1회당 1 크레딧). 따라서 "광고 시청 횟수" 는
 * admob row 수로 집계한다.
 *
 * - [totalWatches] — 누적 광고 시청 완료 횟수.
 * - [watches30d] — 최근 30일 윈도우 (rolling, now-30d 기준).
 * - [watchingUsers] — 광고를 1회 이상 시청한 distinct user (탈퇴로 user_id NULL 인 row 는 제외).
 */
@Serializable
data class AdminAdStats(
    val totalWatches: Long,
    val watches30d: Long,
    val watchingUsers: Long,
)

/**
 * 회원탈퇴(계정 삭제) 요약. account_deletions row 를 집계 — 1 row = 탈퇴 1건.
 *
 * - [totalDeletions] — 누적 탈퇴 수 (V15 이후 적재분. 이전 하드 삭제분은 기록이 없어 미포함).
 * - [deletions30d] — 최근 30일 윈도우 (rolling, now-30d 기준).
 * - [avgTenureDays] / [medianTenureDays] — 가입~탈퇴 체류기간(일). 탈퇴 기록이 없으면 0.
 *   "얼마 쓰고 나갔나" — signed_up_at 과 deleted_at 의 차이로 산출.
 */
@Serializable
data class AdminDeletionStats(
    val totalDeletions: Long,
    val deletions30d: Long,
    val avgTenureDays: Double,
    val medianTenureDays: Double,
)

/**
 * 최근 시간창(기본 24h) 헬스 — Overview 헬스 카드가 "지금" 문제를 잡도록 누적이 아닌 rolling
 * window 로 집계. 성공률/실패율은 프론트가 계산 (분모 0·소표본 가드 포함).
 *
 * - [windowHours] — 집계 창(시간). 기본 24.
 * - [jobsTerminal] / [jobsFailed] — 창 내 생성돼 종료(성공+실패)된 render+separation 잡 수 / 그중 실패.
 * - [upstreamCalls] / [upstreamFailures] — 창 내 Perso 외부호출 수 / 실패 수.
 * - [upstreamP95Ms] — 창 내 호출 latency p95 (Postgres percentile / H2 max 근사).
 */
@Serializable
data class AdminHealth(
    val windowHours: Int,
    val jobsTerminal: Long,
    val jobsFailed: Long,
    val upstreamCalls: Long,
    val upstreamFailures: Long,
    val upstreamP95Ms: Long,
)

/**
 * 일별 탈퇴 수 + provider(google/apple) 분포. 가입(AdminSignupDaily) 대비 이탈 추이 비교용.
 */
@Serializable
data class AdminDeletionDaily(
    val date: String,
    val googleCount: Long,
    val appleCount: Long,
)

/**
 * 사용자 role 변경 요청 바디 (`POST /admin/users/{id}/role`). [role] 은 'admin' | 'user'.
 * 일반 사용자를 운영자로 승격하거나 그 반대로 강등할 때 사용.
 */
@Serializable
data class AdminSetRoleRequest(
    val role: String,
)

/**
 * 감사 로그 1건 (`admin_audit_log`). 컬럼 의미가 [action] 별로 다르다:
 *
 * - `credit_grant`   — [targetEmail] 에게 [amount] 크레딧 지급, [detail] = 사유
 * - `set_role`       — [targetEmail] 의 role 을 [detail] 로 변경
 * - `unblock_rejoin` — 재가입 차단 해제. 대상이 탈퇴자라 [targetEmail] 은 null, [detail] = identity 해시
 *
 * [actorEmail] 은 지급 시점에 denormalize 된 값 — 운영자 계정이 나중에 삭제돼도 남는다.
 * [targetEmail] 은 현재 users row 에서 조회하므로 대상이 탈퇴하면 null 이 된다.
 */
@Serializable
data class AdminAuditEntry(
    val id: Long,
    val at: String,
    val actorEmail: String,
    val action: String,
    val targetUserId: String? = null,
    val targetEmail: String? = null,
    val amount: Int? = null,
    val detail: String? = null,
)

@Serializable
data class AdminAuditResponse(
    val entries: List<AdminAuditEntry>,
    val total: Long,
)

/**
 * 잡 성공/실패 분해 — Overview 의 status 무관 COUNT(*) 가 가리지 못하는 "실제로 동작하는가".
 *
 * 성공 status 가 잡 종류마다 다름: render=COMPLETED, separation=READY. 실패는 둘 다 FAILED.
 * 그 외(PROCESSING/QUEUED/SUBMITTING)는 [inProgress] 로 합산. 성공률은 terminal(succeeded+failed)
 * 기준으로 프론트에서 계산 — in-progress 가 분모를 흐리지 않도록.
 *
 * - [jobType] — 'render' / 'separation'
 * - [client] — separation 은 'mobile'/'plugin' 행으로 분리, render 는 null (모바일 전용).
 * - [total] — succeeded + failed + inProgress (전체 시도)
 */
@Serializable
data class AdminJobStatusBreakdown(
    val jobType: String,
    val client: String? = null,
    val total: Long,
    val succeeded: Long,
    val failed: Long,
    val inProgress: Long,
)

/**
 * 재가입이 차단된 identity 1건 — 탈퇴 후 30일 재가입 차단(가입 보너스 반복 수령 방지) 목록.
 *
 * PII 를 담지 않는다 — 이메일/이름 없이 [provider] 와 시각만으로 대상을 특정한다 (tombstone
 * 자체가 해시만 보관). [identityHash] 는 해제 API 의 키.
 *
 * - [deletedAt] / [blockedUntil] — ISO-8601 instant.
 */
@Serializable
data class AdminBlockedRejoin(
    val identityHash: String,
    val provider: String,
    val deletedAt: String,
    val blockedUntil: String,
)

@Serializable
data class AdminBlockedRejoinsResponse(
    val blocked: List<AdminBlockedRejoin>,
)

/** 차단 해제 결과 — [unblocked] false 면 이미 해제됐거나 만료 정리된 항목. */
@Serializable
data class AdminUnblockRejoinResponse(
    val unblocked: Boolean,
)
