package com.vibi.bff.routes

import com.vibi.bff.model.AdminBlockedRejoin
import com.vibi.bff.model.AdminBlockedRejoinsResponse
import com.vibi.bff.config.envOrProperty
import com.vibi.bff.model.AdminGrantCreditsRequest
import com.vibi.bff.model.AdminGrantCreditsResponse
import com.vibi.bff.model.AdminSetRoleRequest
import com.vibi.bff.model.AdminUnblockRejoinResponse
import com.vibi.bff.model.AdminUserJobsResponse
import com.vibi.bff.model.AdminUsersResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.requireAdmin
import com.vibi.bff.service.AdminGrantResult
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.PersoQuotaCache
import com.vibi.bff.service.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val adminLog = LoggerFactory.getLogger("com.vibi.bff.routes.AdminRoutes")

/**
 * 수동 지급 1회당 상한. 오조작(0 하나 더 입력) 방어 — 더 큰 금액이 필요하면 나눠 지급한다.
 * 24h 총량은 별도로 `ADMIN_GRANT_DAILY_CAP` (default 1000) 이 지급자 기준으로 제한.
 */
const val MAX_ADMIN_GRANT_PER_CALL = 500

/** 지급 사유 최대 길이 — admin_audit_log.detail 컬럼(varchar 500) 보다 넉넉히 짧게. */
private const val MAX_GRANT_REASON_LENGTH = 200

/**
 * 운영자 1인이 24h 동안 지급할 수 있는 크레딧 총량. admin JWT 유출 시 피해 상한.
 * `/admin/users/{id}/credits` 와 `/credits/admin-grant` 가 **같은 한도를 공유**한다 — 한쪽으로
 * 우회해 두 배를 발행하지 못하도록 (집계 소스도 admin_audit_log 하나).
 *
 * `.env` 로도 설정 가능해야 하므로 [envOrProperty] 사용 (System.getenv 만 보면 .env 가 무시된다).
 */
internal fun adminGrantDailyCap(): Int = envOrProperty("ADMIN_GRANT_DAILY_CAP")?.toIntOrNull() ?: 1000

/**
 * `/api/v2/admin/...` — 운영자 대시보드 surface. 모든 라우트 진입 시 [requireAdmin] 으로
 * role=admin JWT 강제. URL slug 숨김 (landing middleware) + role 검사 이중 방어.
 *
 * 대부분 read-only (KPI / 추세 / 사용자·잡 목록). mutating 은 셋 — 크레딧 수동 지급
 * (`POST /users/{userId}/credits`), role 승격/강등 (`POST /users/{userId}/role`), 재가입 차단
 * 해제 (`POST /blocked-rejoins/{hash}/unblock`). 셋 다 `admin_audit_log` 에 흔적을 남기고
 * `GET /audit` 로 열람한다 — 운영자 권한 오남용의 사후 추적 경로.
 *
 * 주의 — KDoc 안에 slash + asterisk 시퀀스는 nested comment 로 파싱돼 컴파일 깨짐.
 * 와일드카드 표현 필요 시 "..." 으로 대체.
 */
fun Route.adminRoutes(
    adminRepository: AdminRepository,
    persoQuotaCache: PersoQuotaCache,
    userRepository: UserRepository,
    jwtSecret: String,
) {
    route("/admin") {

        // 상단 KPI 카드 — 전체 사용자/잡 카운트 + 누적 분량 + 최근 7일 active user.
        get("/overview") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getOverview() }
            call.respond(HttpStatusCode.OK, data.copy(persoAccountCredits = persoQuotaCache.remainingQuota()))
        }

        // 일별 추세. from / to 는 ISO date (YYYY-MM-DD). 누락 시 default 최근 30일.
        // from inclusive, to exclusive (Postgres date_trunc 와 동일 의미).
        get("/stats/daily") {
            call.respondDailyRange(jwtSecret, adminRepository::getDailyStats)
        }

        // 사용자 목록 — 최근 활동 desc. limit 1..200, offset >= 0.
        // q non-blank 면 email/name 부분일치 검색. client=mobile|plugin 이면 해당 클라이언트
        // 사용 이력이 있는 사용자만 (잡 기준 — AdminRepository.getUsersOverview 참조).
        get("/users") {
            call.requireAdmin(jwtSecret)
            val (limit, offset) = call.parsePagination()
            val query = call.request.queryParameters["q"]
            val client = call.request.queryParameters["client"]?.takeIf { it.isNotBlank() }
            if (client != null && client != "mobile" && client != "plugin") {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_client")
            }
            val (rows, total) = withContext(Dispatchers.IO) {
                adminRepository.getUsersOverview(limit, offset, query, client)
            }
            call.respond(HttpStatusCode.OK, AdminUsersResponse(users = rows, total = total))
        }

        // 외부 API (Perso) 일별 호출 카운트 + 실패율 + p95 latency.
        // 비용 예측 + 안정성 가시화.
        get("/stats/external-calls") {
            call.respondDailyRange(jwtSecret, adminRepository::getExternalCallsDaily)
        }

        // 영상 길이 분포 히스토그램. 5 buckets, 항상 5 칸 반환 (0 행도 명시적으로).
        get("/stats/duration-histogram") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) {
                adminRepository.getDurationHistogram()
            }
            call.respond(HttpStatusCode.OK, data)
        }

        // 진행 중 잡 (render + separation 통합). stuck 탐지.
        get("/jobs/active") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) {
                adminRepository.getActiveJobs()
            }
            call.respond(HttpStatusCode.OK, data)
        }

        // 일별 신규 가입자 + provider (google/apple) 분포. iOS-first 정책 검증.
        get("/stats/signups") {
            call.respondDailyRange(jwtSecret, adminRepository::getSignupDaily)
        }

        // 보상형 광고(AdMob) 시청 요약 — 누적/30일 시청 횟수 + 시청 사용자 수.
        get("/ads") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getAdStats() }
            call.respond(HttpStatusCode.OK, data)
        }

        // 최근 시간창 헬스 — Overview 헬스 카드용. hours 1..168 (default 24).
        get("/health") {
            call.requireAdmin(jwtSecret)
            val hours = (call.request.queryParameters["hours"]?.toIntOrNull() ?: 24).coerceIn(1, 168)
            val data = withContext(Dispatchers.IO) { adminRepository.getRecentHealth(hours) }
            call.respond(HttpStatusCode.OK, data)
        }

        // 회원탈퇴 요약 — 누적 + 최근 30일 탈퇴 수 (account_deletions 집계).
        get("/deletions") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getDeletionStats() }
            call.respond(HttpStatusCode.OK, data)
        }

        // 일별 탈퇴 추이 + provider 분포. 가입 추이 대비 이탈 비교.
        get("/stats/deletions") {
            call.respondDailyRange(jwtSecret, adminRepository::getDeletionDaily)
        }

        // 잡 성공/실패 분해 — Overview 의 status 무관 카운트가 가리는 실동작 가시화.
        get("/jobs/status-breakdown") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getJobStatusBreakdown() }
            call.respond(HttpStatusCode.OK, data)
        }

        // 사용자별 render 잡 + 영상 당 분리 횟수.
        get("/users/{userId}/jobs") {
            call.requireAdmin(jwtSecret)
            val userId = call.parseUserId()
            val (limit, offset) = call.parsePagination()
            val (rows, total) = withContext(Dispatchers.IO) {
                adminRepository.getUserJobs(userId, limit, offset)
            }
            call.respond(HttpStatusCode.OK, AdminUserJobsResponse(jobs = rows, total = total))
        }

        // 사용자별 계정 연결/병합 정보 — 연결된 로그인 수단 + 이 계정으로 흡수된 병합 이력(이월 크레딧).
        get("/users/{userId}/account") {
            call.requireAdmin(jwtSecret)
            val userId = call.parseUserId()
            val data = withContext(Dispatchers.IO) { adminRepository.getUserAccount(userId) }
            call.respond(HttpStatusCode.OK, data)
        }

        // 사용자별 크레딧 변동 타임라인 — 가입 보너스/구매/광고/관리자 지급/분리 차감/환불/
        // 병합 이월을 한 스트림으로 (최신순, limit·offset 공유 규약).
        get("/users/{userId}/credits") {
            call.requireAdmin(jwtSecret)
            val userId = call.parseUserId()
            val (limit, offset) = call.parsePagination()
            val data = withContext(Dispatchers.IO) {
                adminRepository.getUserCredits(userId, limit, offset)
            }
            call.respond(HttpStatusCode.OK, data)
        }

        // 운영자 수동 크레딧 지급 — 결제 오류 보상 / 심사용 계정 충전 등. body {credits, reason}.
        // 지급 즉시 잔액이 오르고 admin_audit_log 에 (지급자·수령자·수량·사유) 가 남으며, 같은
        // 내역이 위 타임라인의 '관리자 지급' 이벤트로도 보인다.
        //
        // 사유(reason)를 필수로 받는 이유: 감사 로그의 값은 "왜" 에 있다. 사유 없는 지급은
        // 나중에 부정 사용과 정상 보상을 구분할 수 없다.
        post("/users/{userId}/credits") {
            val principal = call.requireAdmin(jwtSecret)
            val userId = call.parseUserId()
            val body = call.receive<AdminGrantCreditsRequest>()
            if (body.credits !in 1..MAX_ADMIN_GRANT_PER_CALL) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_credits", "1..$MAX_ADMIN_GRANT_PER_CALL")
            }
            val reason = body.reason.trim()
            if (reason.isEmpty()) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "reason_required")
            }
            if (reason.length > MAX_GRANT_REASON_LENGTH) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "reason_too_long", "max $MAX_GRANT_REASON_LENGTH")
            }

            // 상한 검사는 repository 가 지급과 같은 트랜잭션 + 지급자 행 잠금 안에서 수행한다
            // (여기서 미리 조회하면 검사와 지급 사이에 동시 요청이 끼어드는 TOCTOU 가 생긴다).
            val result = withContext(Dispatchers.IO) {
                adminRepository.grantCredits(
                    targetUserId = userId,
                    actorUserId = principal.userId,
                    credits = body.credits,
                    reason = reason,
                    dailyCap = adminGrantDailyCap(),
                )
            }
            when (result) {
                is AdminGrantResult.TargetNotFound -> throw NotFoundException("user not found")
                is AdminGrantResult.CapExceeded -> {
                    adminLog.warn(
                        "admin credit grant daily cap exceeded actor={} recent={} cap={}",
                        principal.userId, result.grantedRecently, result.cap,
                    )
                    throw ApiErrorException(
                        HttpStatusCode.TooManyRequests,
                        "admin_grant_daily_cap_exceeded",
                        "granted=${result.grantedRecently} cap=${result.cap} in 24h",
                    )
                }
                is AdminGrantResult.Granted -> {
                    adminLog.info(
                        "admin credit grant: actor={} target={} +{} balance={}",
                        principal.userId, userId, result.granted, result.balance,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        AdminGrantCreditsResponse(granted = result.granted, balance = result.balance),
                    )
                }
            }
        }

        // 사용자 role 승격/강등. body {role: 'admin'|'user'}.
        // 자기 자신 role 변경은 차단 (마지막 운영자 자가 강등에 의한 lockout 방지 + 오조작 방어).
        // JWT 는 발급 시점 role 을 쓰므로 대상 사용자는 재로그인 후 반영 (AdminRepository.setUserRole 참조).
        post("/users/{userId}/role") {
            val principal = call.requireAdmin(jwtSecret)
            val userId = call.parseUserId()
            val body = call.receive<AdminSetRoleRequest>()
            if (body.role != "admin" && body.role != "user") {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_role")
            }
            if (userId == principal.userId) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "cannot_change_own_role")
            }
            val updated = withContext(Dispatchers.IO) {
                adminRepository.setUserRoleAudited(userId, principal.userId, body.role)
            }
            if (updated == 0) throw NotFoundException("user not found")
            call.respond(HttpStatusCode.OK, AdminSetRoleRequest(role = body.role))
        }

        // 운영자 액션 감사 로그 (최신순) — 크레딧 지급 / role 변경 / 재가입 차단 해제.
        get("/audit") {
            call.requireAdmin(jwtSecret)
            val (limit, offset) = call.parsePagination()
            val data = withContext(Dispatchers.IO) { adminRepository.listAudit(limit, offset) }
            call.respond(HttpStatusCode.OK, data)
        }

        // 탈퇴 후 재가입이 막혀 있는 identity 목록. PII 없음 — provider + 시각만으로 대상 특정.
        get("/blocked-rejoins") {
            call.requireAdmin(jwtSecret)
            val blocked = withContext(Dispatchers.IO) { userRepository.listBlockedRejoins() }
            call.respond(
                HttpStatusCode.OK,
                AdminBlockedRejoinsResponse(
                    blocked = blocked.map {
                        AdminBlockedRejoin(
                            identityHash = it.identityHash,
                            provider = it.provider,
                            deletedAt = it.deletedAt.toString(),
                            blockedUntil = it.blockedUntil.toString(),
                        )
                    },
                ),
            )
        }

        // 차단 해제 — 실수 탈퇴 복구 / 앱 심사자 잠금 해제. 해제 즉시 재가입 가능해진다.
        // DELETE 대신 POST 인 이유: admin UI 의 mutating 액션이 모두 POST 규약(adminPost) 이다.
        post("/blocked-rejoins/{identityHash}/unblock") {
            val principal = call.requireAdmin(jwtSecret)
            val hash = call.parameters["identityHash"]
                ?: throw NotFoundException("identityHash required")
            // PK 는 SHA-256 hex 64자 — 형식이 다르면 조회할 것도 없다.
            if (!hash.matches(Regex("[0-9a-f]{64}"))) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_identity_hash")
            }
            // 해제와 감사 기록은 한 트랜잭션 (AdminRepository.unblockRejoinAudited).
            val unblocked = withContext(Dispatchers.IO) {
                adminRepository.unblockRejoinAudited(hash, principal.userId)
            }
            adminLog.info("rejoin block lifted by admin: hash={} removed={}", hash.take(12), unblocked)
            call.respond(HttpStatusCode.OK, AdminUnblockRejoinResponse(unblocked = unblocked))
        }
    }
}

/**
 * `{userId}` path 파라미터를 UUID 로 파싱. 누락은 404, 형식 오류는 400 `invalid_user_id`.
 * `/users/{userId}/...` 하위 핸들러들이 공유.
 */
private fun ApplicationCall.parseUserId(): UUID {
    val raw = parameters["userId"] ?: throw NotFoundException("userId required")
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_user_id")
    }
}

/**
 * limit / offset 쿼리 파라미터 파싱 — limit 1..200 (default 50), offset >= 0 (default 0).
 * `/users` 와 `/users/{userId}/jobs` 가 공유.
 */
private fun ApplicationCall.parsePagination(): Pair<Int, Int> {
    val limit = (request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
    val offset = (request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    return limit to offset
}

/**
 * admin daily-range 핸들러 공통 골격 — requireAdmin + from/to 파싱 + IO dispatch + respond.
 * `/stats/daily`, `/stats/external-calls`, `/stats/signups` 세 핸들러가 공유.
 */
private suspend fun ApplicationCall.respondDailyRange(
    jwtSecret: String,
    fetch: (Instant, Instant) -> Any,
) {
    requireAdmin(jwtSecret)
    val (from, to) = parseDailyStatsRange(
        request.queryParameters["from"],
        request.queryParameters["to"],
    )
    val data = withContext(Dispatchers.IO) { fetch(from, to) }
    respond(HttpStatusCode.OK, data)
}

/**
 * `from` / `to` 를 ISO date (YYYY-MM-DD) 로 받아 UTC midnight Instant pair 로 변환.
 * 둘 다 누락 시 [최근 30일 전, 내일] 윈도우 default (오늘까지 포함).
 *
 * 잘못된 포맷 / from >= to / 91일 초과 윈도우 모두 400. 90일 cap 은 admin 쿼리 비용 가드.
 */
private fun parseDailyStatsRange(fromParam: String?, toParam: String?): Pair<Instant, Instant> {
    val today = LocalDate.now(ZoneOffset.UTC)
    val toDate = try {
        toParam?.let { LocalDate.parse(it) } ?: today.plusDays(1)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_to_date")
    }
    val fromDate = try {
        fromParam?.let { LocalDate.parse(it) } ?: toDate.minusDays(30)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_from_date")
    }
    if (!fromDate.isBefore(toDate)) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "from_must_be_before_to")
    }
    if (java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) > 90) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "range_too_wide", "max 90 days")
    }
    return fromDate.atStartOfDay(ZoneOffset.UTC).toInstant() to
        toDate.atStartOfDay(ZoneOffset.UTC).toInstant()
}
