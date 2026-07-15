package com.vibi.bff.routes

import com.vibi.bff.model.AdminSetRoleRequest
import com.vibi.bff.model.AdminUserJobsResponse
import com.vibi.bff.model.AdminUsersResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.requireAdmin
import com.vibi.bff.service.AdminRepository
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/api/v2/admin/...` — read-only 분석 surface. 모든 라우트 진입 시 [requireAdmin] 으로
 * role=admin JWT 강제. URL slug 숨김 (landing middleware) + role 검사 이중 방어.
 *
 * 대부분 read-only (KPI / 추세 / 사용자·잡 목록). 유일한 mutating action 은 사용자 role
 * 승격/강등 (`POST /users/{userId}/role`) — 운영자가 일반 사용자를 admin 으로 올린다.
 *
 * 주의 — KDoc 안에 slash + asterisk 시퀀스는 nested comment 로 파싱돼 컴파일 깨짐.
 * 와일드카드 표현 필요 시 "..." 으로 대체.
 */
fun Route.adminRoutes(
    adminRepository: AdminRepository,
    jwtSecret: String,
) {
    route("/admin") {

        // 상단 KPI 카드 — 전체 사용자/잡 카운트 + 누적 분량 + 최근 7일 active user.
        get("/overview") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getOverview() }
            call.respond(HttpStatusCode.OK, data)
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
            val userIdParam = call.parameters["userId"]
                ?: throw NotFoundException("userId required")
            val userId = try {
                UUID.fromString(userIdParam)
            } catch (e: IllegalArgumentException) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_user_id")
            }
            val (limit, offset) = call.parsePagination()
            val (rows, total) = withContext(Dispatchers.IO) {
                adminRepository.getUserJobs(userId, limit, offset)
            }
            call.respond(HttpStatusCode.OK, AdminUserJobsResponse(jobs = rows, total = total))
        }

        // 사용자별 계정 연결/병합 정보 — 연결된 로그인 수단 + 이 계정으로 흡수된 병합 이력(이월 크레딧).
        get("/users/{userId}/account") {
            call.requireAdmin(jwtSecret)
            val userIdParam = call.parameters["userId"]
                ?: throw NotFoundException("userId required")
            val userId = try {
                UUID.fromString(userIdParam)
            } catch (e: IllegalArgumentException) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_user_id")
            }
            val data = withContext(Dispatchers.IO) { adminRepository.getUserAccount(userId) }
            call.respond(HttpStatusCode.OK, data)
        }

        // 사용자 role 승격/강등 — 유일한 mutating admin 액션. body {role: 'admin'|'user'}.
        // 자기 자신 role 변경은 차단 (마지막 운영자 자가 강등에 의한 lockout 방지 + 오조작 방어).
        // JWT 는 발급 시점 role 을 쓰므로 대상 사용자는 재로그인 후 반영 (AdminRepository.setUserRole 참조).
        post("/users/{userId}/role") {
            val principal = call.requireAdmin(jwtSecret)
            val userIdParam = call.parameters["userId"]
                ?: throw NotFoundException("userId required")
            val userId = try {
                UUID.fromString(userIdParam)
            } catch (e: IllegalArgumentException) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_user_id")
            }
            val body = call.receive<AdminSetRoleRequest>()
            if (body.role != "admin" && body.role != "user") {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_role")
            }
            if (userId == principal.userId) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "cannot_change_own_role")
            }
            val updated = withContext(Dispatchers.IO) {
                adminRepository.setUserRole(userId, body.role)
            }
            if (updated == 0) throw NotFoundException("user not found")
            call.respond(HttpStatusCode.OK, AdminSetRoleRequest(role = body.role))
        }
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
