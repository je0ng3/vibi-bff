package com.vibi.bff.service

import com.vibi.bff.db.AccountDeletionsTable
import com.vibi.bff.db.AccountMergesTable
import com.vibi.bff.db.CreditTransactionsTable
import com.vibi.bff.db.RenderJobsTable
import com.vibi.bff.db.SeparationJobsTable
import com.vibi.bff.db.UserCreditsTable
import com.vibi.bff.db.UserIdentitiesTable
import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.model.LinkedIdentity
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/**
 * users 테이블의 `(provider, providerSub)` 기준 single-query upsert.
 *
 * Postgres `INSERT ... ON CONFLICT (...) DO UPDATE` 한 번으로 race condition 까지
 * 해소 — 동시 가입 시 두 트랜잭션이 같은 row 를 안전하게 공유. 결과로 internal UUID 와
 * role 을 반환해 [com.vibi.bff.service.AuthService] 가 JWT sub + role 클레임으로 발급.
 *
 * Exposed `upsert` 가 dialect 추상화 — Postgres 는 `ON CONFLICT`, H2 PostgreSQL mode 는
 * `MERGE` 로 매핑. 단위 테스트는 H2 in-memory 로 동일 경로 검증.
 *
 * role 은 upsert 시 보존된다 — onUpdate 에 명시 안 함. 운영자가 SQL `UPDATE` 로
 * 'admin' 으로 승격한 row 가 재로그인으로 'user' 로 강등되지 않도록.
 *
 * ── 계정 통합(account linking) ──────────────────────────────────────────────
 * users 는 계정 + primary identity 를 그대로 유지하고, 추가로 링크된 두 번째 provider 는
 * [UserIdentitiesTable] 에 secondary identity 로 담는다 (V16). 인증 조회 [resolveOrCreate] 는
 * primary(users) → secondary(user_identities) 순으로 계정을 찾는다. 통합/해제는 [linkOrMerge]
 * / [unlinkIdentity]. 자세한 배경은 V16__user_identities.sql 주석.
 */
/**
 * isNewUser: true 면 이번 upsert 가 신규 row INSERT (재로그인은 false). 호출자가 신규 가입
 * 보너스(크레딧 그랜트) 같은 1회성 사이드이펙트를 분기하기 위해 노출. createdAt == updatedAt
 * 으로 판별 — onUpdate 에서 updatedAt 만 갱신하므로 INSERT 직후 한 번만 둘이 일치한다.
 */
data class UpsertedUser(val id: UUID, val role: String, val isNewUser: Boolean)

class UserRepository {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun upsert(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UpsertedUser = transaction {
        primaryUpsert(provider, providerSub, email, name, picture)
    }

    /**
     * 로그인/가입 진입점 — 계정 통합을 인식하는 upsert.
     *
     * 1) `(provider, providerSub)` 가 다른 계정에 **secondary 로 링크된 identity** 면 그 계정으로
     *    resolve (신규 계정 생성 안 함). 재로그인이므로 isNewUser=false, 가입 보너스 없음.
     * 2) 아니면 기존 [primaryUpsert] — users 의 원자적 upsert (신규 가입 / primary 재로그인).
     *
     * [upsert] 는 primary 경로만 타는 저수준 API (테스트·레거시 caller). 신규 로그인 흐름
     * ([AuthService.completeSignIn]) 은 반드시 본 메서드를 써야 링크된 identity 로 로그인 시
     * spurious 신규 계정이 생기지 않는다.
     */
    fun resolveOrCreate(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UpsertedUser = transaction {
        val linkedAccount = UserIdentitiesTable
            .select(UserIdentitiesTable.accountId)
            .where {
                (UserIdentitiesTable.provider eq provider.dbValue) and
                    (UserIdentitiesTable.providerSub eq providerSub)
            }
            .singleOrNull()
            ?.get(UserIdentitiesTable.accountId)

        if (linkedAccount != null) {
            val now = Instant.now()
            // secondary identity 의 denorm 필드 최신화. users 의 primary email/name 은 secondary
            // 로그인으로 덮지 않는다 (primary 표시값 보존) — updatedAt 만 touch 해 활동 시각 갱신.
            UserIdentitiesTable.update({
                (UserIdentitiesTable.provider eq provider.dbValue) and
                    (UserIdentitiesTable.providerSub eq providerSub)
            }) {
                it[UserIdentitiesTable.email] = email
                it[UserIdentitiesTable.name] = name
                it[UserIdentitiesTable.picture] = picture
            }
            UsersTable.update({ UsersTable.id eq linkedAccount }) {
                it[UsersTable.updatedAt] = now
            }
            val role = UsersTable
                .select(UsersTable.role)
                .where { UsersTable.id eq linkedAccount }
                .single()[UsersTable.role]
            return@transaction UpsertedUser(id = linkedAccount, role = role, isNewUser = false)
        }

        primaryUpsert(provider, providerSub, email, name, picture)
    }

    /** users 테이블 원자적 upsert (primary identity). 반드시 열린 트랜잭션 안에서 호출. */
    private fun primaryUpsert(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UpsertedUser {
        val now = Instant.now()
        UsersTable.upsert(
            UsersTable.provider, UsersTable.providerSub,
            onUpdate = {
                it[UsersTable.email] = email
                it[UsersTable.name] = name
                it[UsersTable.picture] = picture
                it[UsersTable.updatedAt] = now
                // role 은 의도적으로 미터치 — 운영자가 admin 으로 올린 row 가 재로그인으로 강등되지 않도록.
                // createdAt 도 미터치 — 신규 가입 판별 기준이라 INSERT 시점에 고정돼야 함.
            },
        ) {
            it[UsersTable.id] = UUID.randomUUID()
            it[UsersTable.provider] = provider.dbValue
            it[UsersTable.providerSub] = providerSub
            it[UsersTable.email] = email
            it[UsersTable.name] = name
            it[UsersTable.picture] = picture
            it[UsersTable.createdAt] = now
            it[UsersTable.updatedAt] = now
            // role 은 column default ('user') 가 채움.
        }
        // RETURNING 은 dialect 차이가 커 별도 SELECT 로 안정성 우선 (UNIQUE 인덱스라 단일 row).
        val row = UsersTable
            .select(UsersTable.id, UsersTable.role, UsersTable.createdAt, UsersTable.updatedAt)
            .where { (UsersTable.provider eq provider.dbValue) and (UsersTable.providerSub eq providerSub) }
            .single()
        // INSERT 면 onUpdate 가 안 돌아 createdAt == updatedAt (둘 다 위의 now). UPDATE 면
        // onUpdate 가 updatedAt 만 갱신하므로 createdAt != updatedAt. 동시 가입 race 의
        // ON CONFLICT loser 도 UPDATE 분기라 false 로 안전하게 떨어진다.
        return UpsertedUser(
            id = row[UsersTable.id].value,
            role = row[UsersTable.role],
            isNewUser = row[UsersTable.createdAt] == row[UsersTable.updatedAt],
        )
    }

    // ── 계정 통합 (linking / merge) ────────────────────────────────────────────

    /** [linkOrMerge] 결과. 라우트가 HTTP status/응답으로 매핑. */
    sealed interface LinkOutcome {
        /** identity 가 이미 현재 계정에 붙어 있음 (멱등). */
        data object AlreadyLinked : LinkOutcome
        /** 어느 계정에도 없던 identity 를 현재 계정에 새로 링크. */
        data object Linked : LinkOutcome
        /** identity 가 다른 계정 소속이라 그 계정을 현재 계정으로 병합함. */
        data class Merged(val carriedCredits: Int, val newBalance: Int) : LinkOutcome
        /** 현재 계정(또는 병합 대상)에 같은 provider 가 이미 있어 거부 (한 계정당 provider 1개). */
        data object ProviderConflict : LinkOutcome
    }

    /**
     * 로그인된 [currentAccountId] 에 두 번째 provider identity 를 연결한다.
     *
     * - 대상 identity 가 미존재 → 현재 계정에 secondary 로 INSERT ([LinkOutcome.Linked]).
     * - 이미 현재 계정 소속 → 멱등 ([LinkOutcome.AlreadyLinked]).
     * - 다른 계정 B 소속 → **B 를 현재 계정으로 병합** ([mergeAccounts], [LinkOutcome.Merged]).
     * - 현재 계정과 B 가 같은 provider 를 이미 보유 → [LinkOutcome.ProviderConflict] (거부).
     *
     * 단일 트랜잭션 — 병합 중 잔액 합산/재-포인트가 원자적으로 일어난다.
     */
    fun linkOrMerge(
        currentAccountId: UUID,
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): LinkOutcome = transaction {
        val targetAccount = findAccountByIdentity(provider.dbValue, providerSub)
        if (targetAccount == currentAccountId) return@transaction LinkOutcome.AlreadyLinked

        val currentProviders = providersOf(currentAccountId)

        if (targetAccount == null) {
            if (provider.dbValue in currentProviders) return@transaction LinkOutcome.ProviderConflict
            UserIdentitiesTable.insert {
                it[UserIdentitiesTable.accountId] = currentAccountId
                it[UserIdentitiesTable.provider] = provider.dbValue
                it[UserIdentitiesTable.providerSub] = providerSub
                it[UserIdentitiesTable.email] = email
                it[UserIdentitiesTable.name] = name
                it[UserIdentitiesTable.picture] = picture
                it[UserIdentitiesTable.createdAt] = Instant.now()
            }
            return@transaction LinkOutcome.Linked
        }

        // 다른 계정 B 소속 → 병합. 두 계정의 provider 집합이 겹치면(같은 provider 중복) 병합 불가.
        val bProviders = providersOf(targetAccount)
        if (bProviders.any { it in currentProviders }) return@transaction LinkOutcome.ProviderConflict

        val (carried, newBalance) = mergeAccounts(from = targetAccount, into = currentAccountId)
        log.info(
            "account merge: from={} into={} carriedCredits={} newBalance={}",
            targetAccount, currentAccountId, carried, newBalance,
        )
        LinkOutcome.Merged(carriedCredits = carried, newBalance = newBalance)
    }

    /**
     * 계정 B([from]) 를 계정 A([into]) 로 병합. **반드시 열린 트랜잭션 안에서 호출.**
     *
     * 크레딧 합산 규칙: **무료 가입 보너스는 중복 이월하지 않고, 결제·광고로 획득한 크레딧만
     * 합산.** B 의 획득분 `B_earned` = SUM(credit_transactions.credits) — 무료 signup 보너스는
     * credit_ledger(kind='signup')에 있어 애초에 안 잡힌다. 이월액 = min(B.balance, B_earned)
     * (이미 쓴 만큼은 못 넘기고, 남은 잔액은 유료분으로 간주해 사용자에게 유리하게).
     *
     * 잡(render/separation) 과 결제 이력(credit_transactions, **admob 제외**) 의 user_id 는 A 로
     * re-point 해 보존한다 (B 삭제 전에 옮겨야 FK `ON DELETE SET NULL` 로 유실되지 않는다). admob
     * 획득분만 제외 — A 의 일일 광고 상한에 B 의 시청분이 잘못 합산되지 않도록 (아래 참조).
     *
     * **credit_ledger(signup/consume/refund) 는 re-point 하지 않는다** — B 삭제 시 SET NULL 로
     * 익명화된다. 이유:
     *   • consume/refund: B 에 in-flight 분리 잡이 있으면 consume-<jobId> row 가 남는데, 이를 A 로
     *     옮기면 이후 잡 실패 시 [CreditRepository.refund] 가 **예약분 전액**을 A 에 환불해 min(balance,
     *     earned) 캡을 우회, 이월 안 하기로 한 무료 보너스를 되살린다. 병합을 정산 시점으로 보고
     *     예약분은 finalize (orphan 된 consume 의 환불은 no-op — refund 가 userId=NULL 이면 skip).
     *   • signup: A 로 옮기면 A 의 ledger 합이 balance 와 어긋난다 (bonus 는 carry 에서 제외되므로).
     * carry 는 이미 이월분을 balance 에 반영했다 — ledger 는 감사용이라 재구성 소스 아님.
     *
     * B 의 identity(primary + secondary) 는 A 의 secondary 로 편입 후 B users row 삭제
     * (user_credits(B) 는 `ON DELETE CASCADE` 로 정리).
     *
     * 병합 시점에 [AccountMergesTable] 에 감사 1 row (into=A, from B 의 provider/email, carry) 를
     * 적재한다 — 관리자 상세 페이지가 "무슨 계정이 합쳐졌고 몇 크레딧 이월됐나" 를 보여줄 소스.
     *
     * @return (이월된 크레딧, 병합 후 A 의 잔액).
     */
    private fun mergeAccounts(from: UUID, into: UUID): Pair<Int, Int> {
        val now = Instant.now()

        val bBalance = readBalance(from)
        val bEarned = CreditTransactionsTable
            .select(CreditTransactionsTable.credits)
            .where { CreditTransactionsTable.userId eq from }
            .sumOf { it[CreditTransactionsTable.credits] }
        val carry = minOf(bBalance, bEarned).coerceAtLeast(0)
        if (carry > 0) addToBalance(into, carry, now)

        // 잡·결제 이력 re-point (B 삭제로 SET NULL 되기 전에). credit_ledger 는 의도적으로 제외 —
        // 위 KDoc 참조 (예약분 환불로 무료 보너스가 되살아나는 것 차단 + ledger/balance 정합).
        // admob(보상형 광고) 획득분도 제외 — re-point 하면 B 의 최근 24h 광고 시청분이 A 의
        // admobGrantedCreditsSince 합에 잡혀 A 의 일일 광고 상한이 잘못 소진된다(A 는 안 봤는데
        // cap_reached). 제외분은 B 삭제 시 SET NULL 로 익명화 (getAdStats 의 총계엔 그대로 남음).
        CreditTransactionsTable.update({
            (CreditTransactionsTable.userId eq from) and (CreditTransactionsTable.platform neq "admob")
        }) { it[CreditTransactionsTable.userId] = into }
        RenderJobsTable.update({ RenderJobsTable.userId eq from }) { it[RenderJobsTable.userId] = into }
        SeparationJobsTable.update({ SeparationJobsTable.userId eq from }) { it[SeparationJobsTable.userId] = into }

        // B 의 secondary identities → A 로 이전.
        UserIdentitiesTable.update({ UserIdentitiesTable.accountId eq from }) { it[UserIdentitiesTable.accountId] = into }
        // B 의 primary(users row) → A 의 secondary identity 로 편입.
        val b = UsersTable
            .select(UsersTable.provider, UsersTable.providerSub, UsersTable.email, UsersTable.name, UsersTable.picture)
            .where { UsersTable.id eq from }
            .single()
        UserIdentitiesTable.insert {
            it[UserIdentitiesTable.accountId] = into
            it[UserIdentitiesTable.provider] = b[UsersTable.provider]
            it[UserIdentitiesTable.providerSub] = b[UsersTable.providerSub]
            it[UserIdentitiesTable.email] = b[UsersTable.email]
            it[UserIdentitiesTable.name] = b[UsersTable.name]
            it[UserIdentitiesTable.picture] = b[UsersTable.picture]
            it[UserIdentitiesTable.createdAt] = now
        }

        // 병합 감사 row — 관리자 상세 페이지가 "무슨 계정이·언제 합쳐졌고 몇 크레딧 이월됐나" 를
        // 보여줄 유일한 소스 (carry 는 balance 에만 반영되지 B 삭제 후엔 어디서도 재구성 불가).
        // into 삭제 시 CASCADE 로 함께 정리되므로 B 삭제 전 아무 시점에나 insert 하면 된다.
        AccountMergesTable.insert {
            it[AccountMergesTable.intoAccountId] = into
            it[AccountMergesTable.fromProvider] = b[UsersTable.provider]
            it[AccountMergesTable.fromEmail] = b[UsersTable.email]
            it[AccountMergesTable.carriedCredits] = carry
            it[AccountMergesTable.mergedAt] = now
        }

        UsersTable.deleteWhere { UsersTable.id eq from }

        return carry to readBalance(into)
    }

    /** identity 가 속한 계정 UUID (primary=users 먼저, 없으면 secondary=user_identities). 열린 트랜잭션 안. */
    private fun findAccountByIdentity(provider: String, providerSub: String): UUID? {
        val primary = UsersTable
            .select(UsersTable.id)
            .where { (UsersTable.provider eq provider) and (UsersTable.providerSub eq providerSub) }
            .singleOrNull()
            ?.get(UsersTable.id)
            ?.value
        if (primary != null) return primary
        return UserIdentitiesTable
            .select(UserIdentitiesTable.accountId)
            .where { (UserIdentitiesTable.provider eq provider) and (UserIdentitiesTable.providerSub eq providerSub) }
            .singleOrNull()
            ?.get(UserIdentitiesTable.accountId)
    }

    /** 계정에 연결된 provider dbValue 집합 (primary + secondary). 열린 트랜잭션 안. */
    private fun providersOf(accountId: UUID): Set<String> {
        val primary = UsersTable
            .select(UsersTable.provider)
            .where { UsersTable.id eq accountId }
            .singleOrNull()
            ?.get(UsersTable.provider)
        val secondary = UserIdentitiesTable
            .select(UserIdentitiesTable.provider)
            .where { UserIdentitiesTable.accountId eq accountId }
            .map { it[UserIdentitiesTable.provider] }
        return (listOfNotNull(primary) + secondary).toSet()
    }

    /** GET /auth/identities 용 — 계정에 연결된 provider 목록 (primary=users + secondary=user_identities). */
    fun listIdentities(accountId: UUID): List<LinkedIdentity> = transaction {
        val primary = UsersTable
            .select(UsersTable.provider, UsersTable.email)
            .where { UsersTable.id eq accountId }
            .singleOrNull()
            ?.let { LinkedIdentity(it[UsersTable.provider], it[UsersTable.email], primary = true) }
        val secondary = UserIdentitiesTable
            .select(UserIdentitiesTable.provider, UserIdentitiesTable.email)
            .where { UserIdentitiesTable.accountId eq accountId }
            .map { LinkedIdentity(it[UserIdentitiesTable.provider], it[UserIdentitiesTable.email], primary = false) }
        listOfNotNull(primary) + secondary
    }

    /** [unlinkIdentity] 결과. */
    enum class UnlinkOutcome {
        Unlinked,
        /** 계정에 해당 provider identity 가 없음. */
        NotFound,
        /** 마지막 남은 identity 라 해제 불가 (계정엔 최소 1개 로그인 수단 유지). */
        CannotUnlinkLast,
    }

    /**
     * 계정에서 [provider] identity 를 해제. 최소 1개는 남겨야 한다.
     *
     * primary(users) 를 해제하면 secondary 하나를 primary 로 승격(users row 갱신)한 뒤
     * 그 secondary row 를 제거한다 — 계정 UUID/크레딧/잡은 그대로 유지된다.
     */
    fun unlinkIdentity(accountId: UUID, provider: AuthProvider): UnlinkOutcome = transaction {
        val prov = provider.dbValue
        val primaryProvider = UsersTable
            .select(UsersTable.provider)
            .where { UsersTable.id eq accountId }
            .singleOrNull()
            ?.get(UsersTable.provider)
            ?: return@transaction UnlinkOutcome.NotFound

        val secondary = UserIdentitiesTable
            .selectAll()
            .where { UserIdentitiesTable.accountId eq accountId }
            .toList()

        // provider 가 계정에 아예 연결돼 있지 않으면 NotFound — last-identity 가드보다 먼저 검사해야
        // "안 붙은 provider 해제" 요청이 CannotUnlinkLast(409) 로 잘못 매핑되지 않는다.
        val isLinked = prov == primaryProvider ||
            secondary.any { it[UserIdentitiesTable.provider] == prov }
        if (!isLinked) return@transaction UnlinkOutcome.NotFound

        if (1 + secondary.size <= 1) return@transaction UnlinkOutcome.CannotUnlinkLast

        if (prov == primaryProvider) {
            // primary 제거 → secondary 하나를 primary 로 승격.
            val promo = secondary.first()
            UsersTable.update({ UsersTable.id eq accountId }) {
                it[UsersTable.provider] = promo[UserIdentitiesTable.provider]
                it[UsersTable.providerSub] = promo[UserIdentitiesTable.providerSub]
                it[UsersTable.email] = promo[UserIdentitiesTable.email]
                it[UsersTable.name] = promo[UserIdentitiesTable.name] ?: promo[UserIdentitiesTable.email].substringBefore('@')
                it[UsersTable.picture] = promo[UserIdentitiesTable.picture]
                it[UsersTable.updatedAt] = Instant.now()
            }
            UserIdentitiesTable.deleteWhere {
                (UserIdentitiesTable.provider eq promo[UserIdentitiesTable.provider]) and
                    (UserIdentitiesTable.providerSub eq promo[UserIdentitiesTable.providerSub])
            }
        } else {
            // isLinked 가 보장 — secondary 에 존재하므로 정확히 1 row 삭제.
            UserIdentitiesTable.deleteWhere {
                (UserIdentitiesTable.accountId eq accountId) and (UserIdentitiesTable.provider eq prov)
            }
        }
        UnlinkOutcome.Unlinked
    }

    /**
     * 회원탈퇴 — users row 삭제. V5 마이그레이션의 FK cascade 가 자식 row 정리를 책임진다:
     *   • user_credits — `ON DELETE CASCADE` → 함께 삭제
     *   • user_identities — `ON DELETE CASCADE` (V16) → 링크된 secondary identity 도 함께 삭제
     *   • render_jobs / separation_jobs / credit_transactions — `ON DELETE SET NULL` → 익명 row 로 보존
     *
     * 같은 (provider, providerSub) 로 재가입 시점에는 [upsert] 가 새 UUID 의 row 를 생성하므로
     * 이전 잡 분석 row 는 새 사용자와 다른 UUID — 익명 row 로 영구 격리된다.
     *
     * 반환: 실제 삭제된 row 수 (0 = 존재하지 않던 user — 호출자가 200/404 로 분기 가능).
     *
     * 삭제 직전, 같은 트랜잭션에서 [AccountDeletionsTable] 에 탈퇴 사건 1 row 를 적재한다
     * (provider + 가입시각만, PII 없음). 원자적이라 "삭제됐는데 집계 누락"이 발생하지 않는다.
     * user row 가 없으면(이미 삭제/무효 JWT) 로그도 남기지 않는다.
     */
    fun delete(userId: UUID): Int = transaction {
        val snapshot = UsersTable
            .select(UsersTable.provider, UsersTable.createdAt)
            .where { UsersTable.id eq userId }
            .firstOrNull()
        if (snapshot != null) {
            AccountDeletionsTable.insert {
                it[provider] = snapshot[UsersTable.provider]
                it[signedUpAt] = snapshot[UsersTable.createdAt]
                it[deletedAt] = Instant.now()
            }
        }
        UsersTable.deleteWhere { UsersTable.id eq userId }
    }

    /**
     * users row 존재 여부 — 삭제된 계정의 아직-유효한 JWT 가 비용/엔타이틀먼트 mutating
     * endpoint 를 타는 것을 차단하기 위한 fresh check. PK 단일 lookup + limit(1) 이라 저렴.
     * 핫패스(상태 폴링 GET)에는 적용하지 않고 제출(POST) 계열에서만 호출한다.
     */
    fun exists(userId: UUID): Boolean = transaction {
        UsersTable.selectAll().where { UsersTable.id eq userId }.limit(1).any()
    }
}
