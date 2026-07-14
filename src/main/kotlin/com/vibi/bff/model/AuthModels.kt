package com.vibi.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth provider 식별자. `users.provider` 컬럼의 단일 소스 — SQL `CHECK` constraint
 * (V1__users.sql) 도 이 enum 의 [dbValue] 와 동기.
 */
enum class AuthProvider(val dbValue: String) {
    GOOGLE("google"),
    APPLE("apple"),
    ;

    companion object {
        /** wire/DB provider 문자열 → enum (대소문자 무시). 미지원 값은 null — [dbValue] 의 역함수.
         *  provider 파싱의 단일 소스 — 라우트가 개별 `when` 으로 재구현하지 않도록. */
        fun fromDbValue(raw: String?): AuthProvider? =
            raw?.lowercase()?.let { v -> entries.firstOrNull { it.dbValue == v } }
    }
}

/**
 * provider ID Token 검증만 통과한 identity — 아직 DB 계정에 매핑되기 전.
 *
 * 로그인 경로는 이 값을 `resolveOrCreate` 로 계정에 upsert 하고, 계정 통합(링크) 경로는
 * DB upsert 없이 [com.vibi.bff.service.UserRepository.linkOrMerge] 에 그대로 전달한다.
 * AuthService 의 검증 로직을 두 경로가 공유하도록 중간 표현으로 둔다.
 */
data class VerifiedIdentity(
    val provider: AuthProvider,
    val providerSub: String,
    val email: String,
    val name: String,
    val picture: String?,
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
)

/**
 * Apple Sign In 으로 받은 ID Token 교환 요청.
 *
 * - [fullName] — Apple 은 사용자 동의 흐름에서 **최초 1회만** fullName 을 제공한다.
 *   iOS 클라이언트가 그 시점에 받은 값을 그대로 전달; 두 번째 로그인부터는 null.
 *   서버는 신규 가입 시에만 fullName 을 user.name 으로 채우고, 이후엔 DB 의 기존
 *   name 을 보존한다.
 */
@Serializable
data class AppleAuthRequest(
    val idToken: String,
    val fullName: String? = null,
)

/**
 * BFF 가 발급하는 access token 의 user 식별자.
 *
 * - [sub] — **internal UUID 문자열**. user 테이블 PK. Google/Apple 의 native sub 가
 *   아니라 BFF 가 가입 시 발급한 UUID. 향후 IAP `appAccountToken` 으로도 재사용.
 */
@Serializable
data class AuthUser(
    val sub: String,
    val email: String,
    val name: String,
    val picture: String? = null,
    val role: String = "user",
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val expiresAt: Long,
    val user: AuthUser,
)

/** 계정에 연결된 로그인 수단 1개. [primary] 는 최초 가입 provider(항상 1개). */
@Serializable
data class LinkedIdentity(
    val provider: String,
    val email: String,
    val primary: Boolean,
)

/** GET /auth/identities · DELETE /auth/link/{provider} 응답 — 연결된 identity 목록. */
@Serializable
data class IdentitiesResponse(
    val identities: List<LinkedIdentity>,
)

/**
 * POST /auth/link/{provider} 응답.
 *
 * - [status] — "linked" (신규 연결) / "already_linked" (멱등) / "merged" (다른 계정을 흡수).
 * - [mergedCredits] — merged 시 이월된 크레딧 (무료 보너스 제외한 결제·광고분).
 * - [creditBalance] — merged 시 병합 후 현재 계정 잔액.
 * - [identities] — 연결 후 전체 identity 목록 (클라이언트 UI 갱신용).
 */
@Serializable
data class LinkResponse(
    val status: String,
    val creditBalance: Int? = null,
    val mergedCredits: Int? = null,
    val identities: List<LinkedIdentity>,
)

/**
 * Google `tokeninfo` endpoint 응답. exp/iat 등 일부 필드는 문자열로 내려와 String 으로 받음.
 * 검증에 필요한 필드만 정의 — 나머지는 ignoreUnknownKeys 로 무시.
 */
@Serializable
data class GoogleTokenInfo(
    val sub: String,
    val email: String,
    val aud: String,
    val exp: String,
    /** 토큰 발급자. Google ID 토큰은 항상 `accounts.google.com` 또는 `https://accounts.google.com`.
     *  AuthService 가 검증해 Apple 경로(iss 검증)와 대칭을 맞춘다. */
    val iss: String? = null,
    @SerialName("email_verified") val emailVerified: String? = null,
    val name: String? = null,
    val picture: String? = null,
)
