package com.vibi.bff.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.config.envOrProperty
import com.vibi.bff.model.ErrorResponse
import com.vibi.bff.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/** 모바일 앱이 매 요청에 싣는 마케팅 버전 헤더 (vibi-mobile `HEADER_APP_VERSION`). */
const val HEADER_APP_VERSION = "X-App-Version"

/**
 * 최소 지원 앱 버전 게이트. `MIN_APP_VERSION` (예 `1.1.0`) 미만 모바일 클라이언트의 요청을
 * **426 Upgrade Required** 로 끊어 강제 업데이트를 유도한다. env 미설정이면 게이트 자체가 꺼진다
 * (fail-open — 기본은 기존 동작 그대로).
 *
 * 판정:
 * - [HEADER_APP_VERSION] 헤더가 있으면 그 값으로 비교. 파싱 불가한 값(앱이 번들에서 버전을 못 읽어
 *   보내는 `unknown` 등)은 통과 — 헤더를 싣는다는 것 자체가 헤더 도입(1.1.0) 이후 빌드라는 뜻이라,
 *   조회 실패로 사용자를 업데이트 화면에 가두는 fail-closed 를 피한다.
 * - 헤더가 없으면 헤더 도입(모바일 1.1.0) **이전 빌드**로 보되, 모바일이라고 확인되는 요청만
 *   막는다 — JWT `client` claim 이 `mobile` 이거나, 모바일 전용 로그인 교환 경로일 때.
 *   UXP 패널(device-flow, claim `plugin`)·admin SPA·SSV 콜백은 헤더가 없어도 통과한다.
 *
 * 게이트 대상은 `/api/v2` 아래 앱 API 뿐 — health probe·device 로그인 페이지·admin·정적 리소스는
 * 건드리지 않는다.
 */
fun Application.configureAppVersionGate(jwtSecret: String) {
    val log = LoggerFactory.getLogger("com.vibi.bff.plugins.AppVersionGate")
    val raw = envOrProperty("MIN_APP_VERSION")
    val minVersion = raw?.let(::parseVersion)
    if (minVersion == null) {
        if (raw != null) log.warn("MIN_APP_VERSION='{}' is not a valid version — gate disabled", raw)
        log.info("App version gate disabled (MIN_APP_VERSION unset)")
        return
    }
    log.info("App version gate enabled — minimum app version {}", raw)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.isVersionGated()) return@intercept

        val header = call.request.header(HEADER_APP_VERSION)
        val outdated = if (header != null) {
            val version = parseVersion(header)
            version != null && compareVersions(version, minVersion) < 0
        } else {
            call.isLegacyMobileClient(jwtSecret, path)
        }
        if (outdated) {
            log.info("426 upgrade_required: path={} version={}", path, header ?: "(none)")
            call.respond(
                HttpStatusCode.UpgradeRequired,
                ErrorResponse(
                    error = "app_update_required",
                    detail = "This app version is no longer supported. Please update to continue.",
                ),
            )
            finish()
        }
    }
}

/** 모바일 전용 ID Token 교환 — UXP 패널은 device-flow 를 쓰므로 이 경로로 들어오지 않는다. */
private val MOBILE_LOGIN_PATHS = setOf("/api/v2/auth/google", "/api/v2/auth/apple")

/**
 * 게이트를 적용할 경로인지. 앱 API(`/api/v2`) 만 대상이며, 그 안에서도 앱이 아닌 호출자가 쓰는
 * 경로는 제외한다 — device-flow 로그인(UXP 패널), admin, AdMob SSV 서버 콜백.
 */
private fun String.isVersionGated(): Boolean {
    if (!startsWith("/api/v2/")) return false
    if (startsWith("/api/v2/admin")) return false
    if (startsWith("/api/v2/auth/device")) return false
    if (startsWith("/api/v2/auth/google/start") || startsWith("/api/v2/auth/google/callback")) return false
    if (startsWith("/api/v2/credits/admob-ssv")) return false
    return true
}

/**
 * 버전 헤더가 없는 요청이 "헤더 도입 이전 모바일 빌드" 인지. 서명 검증을 통과한 JWT 의
 * `client` claim 이 유일한 근거 — 위조 토큰으로 게이트를 통과당하지 않게 [requireUser] 와 동일한
 * 검증을 쓴다. 토큰이 없으면 모바일 전용 로그인 경로일 때만 구버전으로 본다.
 *
 * 검증 실패(만료·위조)는 호출자를 알 수 없는 상태 — 게이트가 삼키지 않고 통과시켜 라우트의
 * 인증이 401 을 내게 둔다. 만료된 패널 토큰이 426 으로 둔갑해 재로그인 흐름이 깨지는 것 방지.
 */
private fun ApplicationCall.isLegacyMobileClient(jwtSecret: String, path: String): Boolean {
    val token = request.header("Authorization")
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return path in MOBILE_LOGIN_PATHS
    val decoded = runCatching {
        JWT.require(Algorithm.HMAC256(jwtSecret))
            .withIssuer(AuthService.ISSUER)
            .withAudience(AuthService.AUDIENCE)
            .build()
            .verify(token)
    }.getOrNull() ?: return false
    // claim 없는 구버전 JWT 는 mobile fallback (requireUser 와 동일 규약).
    val client = decoded.getClaim("client").asString()?.takeIf { it.isNotBlank() }
        ?: AuthService.CLIENT_MOBILE
    return client == AuthService.CLIENT_MOBILE
}

/** `"1.1.0"` → `[1, 1, 0]`. 숫자가 아닌 값이 섞이면 null (= 판정 불가). */
private fun parseVersion(raw: String): List<Int>? {
    val parts = raw.trim().split('.')
    if (parts.isEmpty() || parts.size > 4) return null
    return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
}

/** 자리수가 달라도 짧은 쪽을 0 으로 채워 비교 (`1.1` == `1.1.0`). */
private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val cmp = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
        if (cmp != 0) return cmp
    }
    return 0
}
