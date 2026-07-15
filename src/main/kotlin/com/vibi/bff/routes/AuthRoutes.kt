package com.vibi.bff.routes

import com.vibi.bff.model.AppleAuthRequest
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.model.GoogleAuthRequest
import com.vibi.bff.model.IdentitiesResponse
import com.vibi.bff.model.LinkResponse
import com.vibi.bff.model.LinkedIdentity
import com.vibi.bff.model.VerifiedIdentity
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.AccountContentEraser
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.routes.AuthRoutes")

/**
 * 소셜 로그인 게이트웨이 + 회원탈퇴.
 *
 * 모바일 클라이언트(iOS GoogleSignIn / Apple AuthenticationServices / Android Credential
 * Manager)가 native SDK 로 받은 ID Token 을 BFF 에 전달하면, BFF 가 provider 별 검증
 * (Google `tokeninfo` / Apple JWKS) 후 user 테이블 upsert → 자체 access token (HS256 JWT)
 * 을 발급한다. JWT 의 `sub` 는 BFF internal UUID (provider sub 가 아님) — IAP
 * `appAccountToken` 으로도 그대로 재사용된다.
 *
 * `DELETE /auth/account` 는 인증된 사용자 본인을 영구 삭제 (FK cascade — V5 마이그레이션).
 * Apple 의 App Store 가이드라인 5.1.1(v) 가 요구하는 in-app account deletion 충족.
 */
fun Route.authRoutes(
    authService: AuthService,
    userRepository: UserRepository,
    accountContentEraser: AccountContentEraser,
    jwtSecret: String,
) {
    route("/auth") {
        post("/google") {
            val req = call.receive<GoogleAuthRequest>()
            val user = authService.verifyGoogleIdToken(req.idToken)
            val response = authService.issueAccessToken(user)
            call.respond(HttpStatusCode.OK, response)
        }

        post("/apple") {
            val req = call.receive<AppleAuthRequest>()
            val user = authService.verifyAppleIdToken(req.idToken, req.fullName)
            val response = authService.issueAccessToken(user)
            call.respond(HttpStatusCode.OK, response)
        }

        // ── 계정 통합(링크) ─────────────────────────────────────────────────────
        // 로그인된 사용자가 두 번째 provider 를 자기 계정에 연결. 대상 provider 가 이미 독립
        // 계정이면 그 계정을 현재 계정으로 병합(크레딧 합산 — 무료 보너스 제외)한다.
        // /auth 하위라 RL_AUTH(IP 레이트리밋) 를 그대로 상속.
        route("/link") {
            post("/google") {
                val principal = call.requireUser(jwtSecret)
                val req = call.receive<GoogleAuthRequest>()
                val identity = authService.verifyGoogleIdentity(req.idToken)
                call.respondLink(userRepository, principal.userId, identity)
            }

            post("/apple") {
                val principal = call.requireUser(jwtSecret)
                val req = call.receive<AppleAuthRequest>()
                val identity = authService.verifyAppleIdentity(req.idToken, req.fullName)
                call.respondLink(userRepository, principal.userId, identity)
            }
        }

        // provider identity 해제. 마지막 하나는 남겨야 하며(로그인 수단 유실 방지), primary
        // 를 해제하면 남은 secondary 를 primary 로 승격한다 — 계정 UUID/크레딧은 유지.
        delete("/link/{provider}") {
            val principal = call.requireUser(jwtSecret)
            val provider = parseProvider(call.parameters["provider"])
            val outcome = withContext(Dispatchers.IO) {
                userRepository.unlinkIdentity(principal.userId, provider)
            }
            when (outcome) {
                UserRepository.UnlinkOutcome.CannotUnlinkLast ->
                    throw ApiErrorException(HttpStatusCode.Conflict, "cannot_unlink_last_identity")
                UserRepository.UnlinkOutcome.NotFound ->
                    throw ApiErrorException(HttpStatusCode.NotFound, "identity_not_linked")
                UserRepository.UnlinkOutcome.Unlinked -> {
                    log.info("identity unlinked: user={} provider={}", principal.userId, provider.dbValue)
                    call.respond(HttpStatusCode.OK, IdentitiesResponse(currentIdentities(userRepository, principal.userId)))
                }
            }
        }

        // 현재 계정에 연결된 provider 목록 (모바일 '계정 연결' 화면).
        get("/identities") {
            val principal = call.requireUser(jwtSecret)
            call.respond(HttpStatusCode.OK, IdentitiesResponse(currentIdentities(userRepository, principal.userId)))
        }

        delete("/account") {
            val principal = call.requireUser(jwtSecret)
            // GDPR 17조 / CCPA right-to-erasure: users row 삭제 BEFORE 에 사용자 콘텐츠(분리 스템·
            // 렌더 산출물)를 로컬 디스크 + R2 에서 제거한다. user_id 가 SET NULL 되기 전에 잡을
            // enumerate 해야 하므로 순서가 load-bearing. best-effort — 콘텐츠 삭제 실패가 계정
            // 삭제 자체를 막지 않도록 runCatching (잔존분은 R2 lifecycle + uploads GC 가 안전망).
            runCatching { accountContentEraser.erase(principal.userId) }
                .onFailure { e -> log.warn("account content erase failed (proceeding with row delete) user={}: {}", principal.userId, e.message) }
            // 이후 users row 삭제 → FK cascade 가 user_credits(CASCADE) / render_jobs ·
            // separation_jobs · credit_transactions(SET NULL — 익명 분석/감사 보존) 정리.
            //
            // TODO(apple): Apple Sign In revocation API 호출 추가 — `POST https://appleid.apple.com/auth/revoke`
            //   로 refresh token 폐기 필요. App Store 가이드라인 5.1.1(v) 권장.
            val deleted = withContext(Dispatchers.IO) { userRepository.delete(principal.userId) }
            log.info("account deleted: user={} rows={}", principal.userId, deleted)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** "google"/"apple" (대소문자 무시) → [AuthProvider]. 그 외는 400. 매핑은 enum 이 소유. */
private fun parseProvider(raw: String?): AuthProvider =
    AuthProvider.fromDbValue(raw)
        ?: throw ApiErrorException(HttpStatusCode.BadRequest, "unsupported_provider")

private suspend fun currentIdentities(
    userRepository: UserRepository,
    accountId: java.util.UUID,
): List<LinkedIdentity> = withContext(Dispatchers.IO) {
    userRepository.listIdentities(accountId)
}

/**
 * [UserRepository.linkOrMerge] 실행 후 결과를 HTTP 로 매핑.
 * 같은 provider 중복은 409, 그 외는 200 + 갱신된 identity 목록 (+병합 시 이월 크레딧·잔액).
 */
private suspend fun ApplicationCall.respondLink(
    userRepository: UserRepository,
    accountId: java.util.UUID,
    identity: VerifiedIdentity,
) {
    val outcome = withContext(Dispatchers.IO) {
        userRepository.linkOrMerge(
            currentAccountId = accountId,
            provider = identity.provider,
            providerSub = identity.providerSub,
            email = identity.email,
            name = identity.name,
            picture = identity.picture,
        )
    }
    val identities = currentIdentities(userRepository, accountId)
    val response = when (outcome) {
        UserRepository.LinkOutcome.AlreadyLinked ->
            LinkResponse(status = "already_linked", identities = identities)
        UserRepository.LinkOutcome.Linked ->
            LinkResponse(status = "linked", identities = identities)
        is UserRepository.LinkOutcome.Merged ->
            LinkResponse(
                status = "merged",
                creditBalance = outcome.newBalance,
                mergedCredits = outcome.carriedCredits,
                identities = identities,
            )
        UserRepository.LinkOutcome.ProviderConflict ->
            throw ApiErrorException(HttpStatusCode.Conflict, "provider_already_linked")
    }
    respond(HttpStatusCode.OK, response)
}
