package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.AccountDeletionsTable
import com.vibi.bff.db.AccountMergesTable
import com.vibi.bff.db.CreditTransactionsTable
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.db.DeletedIdentitiesTable
import com.vibi.bff.db.UserIdentitiesTable
import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.SIGNUP_BONUS_CREDITS
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class UserRepositoryTest {

    private val testDb = TestDatabase()
    private lateinit var repo: UserRepository

    @BeforeTest
    fun setup() {
        // H2 PostgreSQL mode + Flyway. TestDatabase 가 매 테스트 fresh DB + teardown 에서
        // Exposed 레지스트리 정리(공유 IO 스레드 cross-test 오염 방지).
        testDb.start()
        repo = UserRepository()
    }

    @AfterTest
    fun teardown() {
        testDb.stop()
    }

    @Test
    fun `upsert returns same UUID for same provider+sub`() {
        val first = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        val second = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        assertEquals(first.id, second.id)
        // isNewUser 는 첫 호출에서만 true (그 외 필드는 UPDATE 분기로 동일하게 떨어짐).
        assertEquals(true, first.isNewUser)
        assertEquals(false, second.isNewUser)
    }

    @Test
    fun `upsert returns different UUID for different provider sub`() {
        val google = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        val apple = repo.upsert(AuthProvider.APPLE, "a-1", "a@example.com", "Alice", null)
        assertNotEquals(google.id, apple.id)
    }

    @Test
    fun `upsert updates email and name on existing row`() {
        repo.upsert(AuthProvider.APPLE, "a-1", "old@example.com", "Old Name", null)
        repo.upsert(AuthProvider.APPLE, "a-1", "new@example.com", "New Name", "pic.png")

        val row = transaction {
            UsersTable.selectAll().where { UsersTable.providerSub eq "a-1" }.single()
        }
        assertEquals("new@example.com", row[UsersTable.email])
        assertEquals("New Name", row[UsersTable.name])
        assertEquals("pic.png", row[UsersTable.picture])
    }

    @Test
    fun `upsert returns default role 'user' for new row`() {
        val upserted = repo.upsert(AuthProvider.GOOGLE, "g-new", "x@example.com", "X", null)
        assertEquals("user", upserted.role)
    }

    @Test
    fun `upsert preserves admin role across re-login`() {
        val first = repo.upsert(AuthProvider.GOOGLE, "g-admin", "ops@example.com", "Ops", null)
        // 운영자가 SQL 로 admin 승격한 상황 시뮬레이션.
        transaction {
            UsersTable.update({ UsersTable.id eq first.id }) { it[role] = "admin" }
        }
        // 같은 사용자가 재로그인 → upsert 가 'admin' 을 'user' 로 강등시키면 안 됨.
        val second = repo.upsert(AuthProvider.GOOGLE, "g-admin", "ops@example.com", "Ops Renamed", null)
        assertEquals(first.id, second.id)
        assertEquals("admin", second.role)
    }

    @Test
    fun `exists is true for upserted user, false for unknown and after delete`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-exists", "e@example.com", "E", null)
        assertTrue(repo.exists(u.id))
        assertFalse(repo.exists(UUID.randomUUID())) // 본 적 없는 UUID
        repo.delete(u.id)
        assertFalse(repo.exists(u.id))               // 삭제 후 false → A-1 차단의 기반
    }

    @Test
    fun `delete records an account_deletions audit row with provider`() {
        val u = repo.upsert(AuthProvider.APPLE, "a-del", "d@example.com", "D", null)
        repo.delete(u.id)

        val rows = transaction { AccountDeletionsTable.selectAll().toList() }
        assertEquals(1, rows.size)
        assertEquals("apple", rows.single()[AccountDeletionsTable.provider])

        // 집계도 반영 — 누적/30일 모두 방금 탈퇴 1건.
        val stats = AdminRepository(repo).getDeletionStats()
        assertEquals(1L, stats.totalDeletions)
        assertEquals(1L, stats.deletions30d)
    }

    @Test
    fun `delete of unknown user records no audit row`() {
        repo.delete(UUID.randomUUID())
        val count = transaction { AccountDeletionsTable.selectAll().count() }
        assertEquals(0L, count)
    }

    // ── 탈퇴 후 재가입 차단 (deleted_identities tombstone) ─────────────────────

    @Test
    fun `isBlockedRejoin is true within window after delete, false for other identities`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-rejoin", "r@example.com", "R", null)
        assertFalse(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-rejoin")) // 계정 존재 → 차단 아님
        repo.delete(u.id)
        assertTrue(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-rejoin"))
        // 다른 sub / 다른 provider 는 무관.
        assertFalse(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-other"))
        assertFalse(repo.isBlockedRejoin(AuthProvider.APPLE, "g-rejoin"))
    }

    @Test
    fun `delete tombstones linked secondary identities too`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-pri", "p@example.com", "P", null)
        repo.linkOrMerge(u.id, AuthProvider.APPLE, "a-sec", "p@icloud.com", "P", null)
        repo.delete(u.id)
        assertTrue(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-pri"))
        assertTrue(repo.isBlockedRejoin(AuthProvider.APPLE, "a-sec"))
    }

    @Test
    fun `isBlockedRejoin ignores expired tombstones`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-old", "o@example.com", "O", null)
        repo.delete(u.id)
        // tombstone 을 창 밖(31일 전)으로 밀어 만료 시나리오 재현.
        transaction {
            DeletedIdentitiesTable.update {
                it[deletedAt] = java.time.Instant.now().minus(java.time.Duration.ofDays(31))
            }
        }
        assertFalse(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-old"))
    }

    @Test
    fun `listBlockedRejoins exposes provider and window, hiding expired rows`() {
        val g = repo.upsert(AuthProvider.GOOGLE, "g-list", "g@example.com", "G", null)
        val a = repo.upsert(AuthProvider.APPLE, "a-list", "a@example.com", "A", null)
        repo.delete(g.id)
        repo.delete(a.id)

        val blocked = repo.listBlockedRejoins()
        assertEquals(2, blocked.size)
        // 최근 탈퇴 순 — 목록 상단이 운영자가 방금 막힌 대상(심사자 등)을 찾는 자리.
        assertEquals(setOf("google", "apple"), blocked.map { it.provider }.toSet())
        // 차단 만료 시각이 노출돼야 "언제 풀리는지" 를 UI 가 표시할 수 있다.
        blocked.forEach {
            assertEquals(it.deletedAt.plus(UserRepository.REJOIN_BLOCK), it.blockedUntil)
        }

        // 창 밖으로 민 row 는 차단력이 없으므로 목록에서도 빠진다.
        transaction {
            DeletedIdentitiesTable.update({
                DeletedIdentitiesTable.identityHash eq UserRepository.identityHash("google", "g-list")
            }) {
                it[deletedAt] = java.time.Instant.now().minus(java.time.Duration.ofDays(31))
            }
        }
        assertEquals(listOf("apple"), repo.listBlockedRejoins().map { it.provider })
    }

    @Test
    fun `unblockRejoin lifts the block so the identity can sign up again`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-unblock", "u@example.com", "U", null)
        repo.delete(u.id)
        assertTrue(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-unblock"))

        val hash = repo.listBlockedRejoins().single().identityHash
        assertTrue(repo.unblockRejoin(hash))

        assertFalse(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-unblock"))
        assertTrue(repo.listBlockedRejoins().isEmpty())
        // 이미 없는 행 해제는 false — UI 가 "이미 해제됨" 을 구분할 수 있다.
        assertFalse(repo.unblockRejoin(hash))
    }

    @Test
    fun `tombstone does not block once an account exists again`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-back", "b@example.com", "B", null)
        repo.delete(u.id)
        // (예: 운영자 수동 복구 등으로) 계정이 다시 생기면 로그인은 차단하지 않는다.
        repo.upsert(AuthProvider.GOOGLE, "g-back", "b@example.com", "B", null)
        assertFalse(repo.isBlockedRejoin(AuthProvider.GOOGLE, "g-back"))
    }

    // ── 계정 통합 (linking / merge) ────────────────────────────────────────────

    @Test
    fun `resolveOrCreate resolves a linked secondary identity to the existing account`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)

        // Apple identity 로 재로그인 → 새 계정을 만들지 않고 A 로 resolve (isNewUser=false).
        val resolved = repo.resolveOrCreate(AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        assertEquals(a.id, resolved.id)
        assertFalse(resolved.isNewUser)
        // users 테이블엔 여전히 A 한 row 뿐 (apple 이 spurious 계정을 만들지 않음).
        val userCount = transaction { UsersTable.selectAll().count() }
        assertEquals(1L, userCount)
    }

    @Test
    fun `linkOrMerge links a fresh identity as secondary`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        val outcome = repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        assertEquals(UserRepository.LinkOutcome.Linked, outcome)

        val identities = repo.listIdentities(a.id)
        assertEquals(2, identities.size)
        assertEquals(setOf("google", "apple"), identities.map { it.provider }.toSet())
        assertEquals(1, identities.count { it.primary })
    }

    @Test
    fun `linkOrMerge is idempotent when identity already linked`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        val again = repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        assertEquals(UserRepository.LinkOutcome.AlreadyLinked, again)
        assertEquals(2, repo.listIdentities(a.id).size)
    }

    @Test
    fun `linkOrMerge rejects a second identity of the same provider`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        // 다른 google sub 를 링크 시도 → 계정당 provider 1개라 거부.
        val outcome = repo.linkOrMerge(a.id, AuthProvider.GOOGLE, "g-2", "other@example.com", "Alice2", null)
        assertEquals(UserRepository.LinkOutcome.ProviderConflict, outcome)
        assertEquals(1, repo.listIdentities(a.id).size)
    }

    @Test
    fun `linkOrMerge merges another account carrying only earned credits not the free bonus`() {
        val credits = CreditRepository()
        // A (google): 무료 보너스만.
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id)
        // B (apple): 무료 보너스 + 획득 크레딧 5 (credit_transactions 1 row). 병합 합산은 platform
        // 무관하게 credit_transactions 를 SUM 하므로, H2 CHECK 가 허용하는 'google' 로 획득분 표현
        // (실제 광고 경로는 platform='admob' — prod Postgres 에선 동일하게 SUM 에 잡힘).
        val b = repo.upsert(AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        credits.grantSignupBonus(b.id)
        credits.grantPurchase(b.id, "google", "earn-1", "rewarded", 5)
        assertEquals(SIGNUP_BONUS_CREDITS + 5, credits.balance(b.id))

        val outcome = repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        // 무료 보너스(10)는 중복 이월 안 됨 — 획득분(5)만 합산.
        assertTrue(outcome is UserRepository.LinkOutcome.Merged)
        val merged = outcome as UserRepository.LinkOutcome.Merged
        assertEquals(5, merged.carriedCredits)
        assertEquals(SIGNUP_BONUS_CREDITS + 5, merged.newBalance)
        assertEquals(SIGNUP_BONUS_CREDITS + 5, credits.balance(a.id))

        // B 계정 소멸, 두 identity 모두 A 로.
        assertFalse(repo.exists(b.id))
        assertEquals(setOf("google", "apple"), repo.listIdentities(a.id).map { it.provider }.toSet())
        // B 의 결제 이력이 A 로 re-point (감사 보존).
        val txOwner = transaction {
            CreditTransactionsTable
                .select(CreditTransactionsTable.userId)
                .where { CreditTransactionsTable.transactionId eq "earn-1" }
                .single()[CreditTransactionsTable.userId]
        }
        assertEquals(a.id, txOwner)
    }

    @Test
    fun `linkOrMerge writes an account_merges audit row for the absorbing account`() {
        val credits = CreditRepository()
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id)
        val b = repo.upsert(AuthProvider.APPLE, "ap-1", "b@icloud.com", "Bob", null)
        credits.grantSignupBonus(b.id)
        credits.grantPurchase(b.id, "google", "earn-1", "rewarded", 5)

        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "b@icloud.com", "Bob", null)

        val row = transaction {
            AccountMergesTable.selectAll()
                .where { AccountMergesTable.intoAccountId eq a.id }
                .single()
        }
        assertEquals("apple", row[AccountMergesTable.fromProvider])
        assertEquals("b@icloud.com", row[AccountMergesTable.fromEmail])
        assertEquals(5, row[AccountMergesTable.carriedCredits])
    }

    @Test
    fun `deleting the absorbing account cascades its merge audit rows away`() {
        val credits = CreditRepository()
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id)
        val b = repo.upsert(AuthProvider.APPLE, "ap-1", "b@icloud.com", "Bob", null)
        credits.grantSignupBonus(b.id)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "b@icloud.com", "Bob", null)

        repo.delete(a.id)

        val remaining = transaction {
            AccountMergesTable.selectAll()
                .where { AccountMergesTable.intoAccountId eq a.id }
                .count()
        }
        assertEquals(0L, remaining)
    }

    @Test
    fun `linkOrMerge caps carried credits at remaining balance when earned credits already spent`() {
        val credits = CreditRepository()
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id)
        val b = repo.upsert(AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        credits.grantSignupBonus(b.id) // + 무료 보너스
        credits.grantPurchase(b.id, "google", "earn-1", "rewarded", 5) // +5 earned
        // 잔액이 earned(5) 보다 적게 3 만 남도록 예약 — 보너스 상수 변경에 무관하게 재현.
        credits.reserve(b.id, "job-1", SIGNUP_BONUS_CREDITS + 2)

        val outcome = repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null) as UserRepository.LinkOutcome.Merged
        // carry = min(balance=3, earned=5) = 3.
        assertEquals(3, outcome.carriedCredits)
        assertEquals(SIGNUP_BONUS_CREDITS + 3, credits.balance(a.id))
    }

    @Test
    fun `merging an account with an in-flight job does not refund the reserve into the absorbing account`() {
        // 회귀 가드: 병합이 B 의 consume ledger row 를 A 로 re-point 하면, 잡 실패 시 예약분 전액이
        // A 로 환불돼 min(balance, earned) 캡을 우회(무료 보너스 되살아남). 병합은 credit_ledger 를
        // re-point 하지 않으므로 orphan 된 consume 의 환불은 no-op 여야 한다.
        val credits = CreditRepository()
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        credits.grantSignupBonus(a.id) // A balance = 보너스
        val b = repo.upsert(AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        credits.grantSignupBonus(b.id) // + 무료 보너스
        credits.grantPurchase(b.id, "google", "earn-1", "rewarded", 5) // +5 earned
        credits.reserve(b.id, "job-1", SIGNUP_BONUS_CREDITS + 2) // in-flight 잡 → balance 3

        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null) // carry=min(3,5)=3
        assertEquals(SIGNUP_BONUS_CREDITS + 3, credits.balance(a.id))

        // 병합 후 B 의 잡이 실패 → 환불 콜백. orphan 된 consume 라 A 잔액은 변하지 않아야 한다.
        val refunded = credits.refund("job-1")
        assertNull(refunded) // consume.user_id 가 NULL → refund no-op
        assertEquals(SIGNUP_BONUS_CREDITS + 3, credits.balance(a.id)) // 예약분이 A 로 환불되지 않음 = 누수 없음
    }

    @Test
    fun `linkOrMerge rejects merge when both accounts share a provider`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null) // A = {google, apple}
        val b = repo.upsert(AuthProvider.APPLE, "ap-2", "b@icloud.com", "Bob", null)       // B = {apple}

        // B 의 apple 을 A 에 링크 시도 → A 도 apple 보유 → 병합 불가.
        val outcome = repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-2", "b@icloud.com", "Bob", null)
        assertEquals(UserRepository.LinkOutcome.ProviderConflict, outcome)
        assertTrue(repo.exists(b.id)) // B 그대로.
    }

    @Test
    fun `unlink secondary identity removes it and keeps account`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)

        val outcome = repo.unlinkIdentity(a.id, AuthProvider.APPLE)
        assertEquals(UserRepository.UnlinkOutcome.Unlinked, outcome)
        val identities = repo.listIdentities(a.id)
        assertEquals(1, identities.size)
        assertEquals("google", identities.single().provider)
        assertTrue(identities.single().primary)
    }

    @Test
    fun `unlink primary identity promotes a secondary to primary`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)

        val outcome = repo.unlinkIdentity(a.id, AuthProvider.GOOGLE)
        assertEquals(UserRepository.UnlinkOutcome.Unlinked, outcome)
        // apple 이 primary 로 승격, 같은 계정 UUID 유지.
        val identities = repo.listIdentities(a.id)
        assertEquals(1, identities.size)
        assertEquals("apple", identities.single().provider)
        assertTrue(identities.single().primary)
        // users row 의 primary provider 도 apple 로 갱신, secondary row 는 사라짐.
        val (provider, secondaryCount) = transaction {
            val p = UsersTable.select(UsersTable.provider).where { UsersTable.id eq a.id }.single()[UsersTable.provider]
            val c = UserIdentitiesTable.selectAll().where { UserIdentitiesTable.accountId eq a.id }.count()
            p to c
        }
        assertEquals("apple", provider)
        assertEquals(0L, secondaryCount)
    }

    @Test
    fun `unlink last remaining identity is rejected`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        val outcome = repo.unlinkIdentity(a.id, AuthProvider.GOOGLE)
        assertEquals(UserRepository.UnlinkOutcome.CannotUnlinkLast, outcome)
        assertTrue(repo.exists(a.id))
    }

    @Test
    fun `unlink a provider that is not linked returns NotFound not CannotUnlinkLast`() {
        // google 만 있는 계정에 apple 해제 요청 → apple 은 안 붙어 있으므로 NotFound.
        // (last-identity 가드가 먼저 발동해 CannotUnlinkLast 로 잘못 매핑되면 안 됨.)
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        assertEquals(UserRepository.UnlinkOutcome.NotFound, repo.unlinkIdentity(a.id, AuthProvider.APPLE))
        assertTrue(repo.exists(a.id))
    }

    @Test
    fun `unlink drops to single identity then further unlink is rejected as last`() {
        val a = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        repo.linkOrMerge(a.id, AuthProvider.APPLE, "ap-1", "a@icloud.com", "Alice", null)
        // apple 제거 → google primary 하나만 남음.
        assertEquals(UserRepository.UnlinkOutcome.Unlinked, repo.unlinkIdentity(a.id, AuthProvider.APPLE))
        // 남은 마지막(google) 언링크는 거부 — 계정엔 최소 1개 로그인 수단 유지.
        assertEquals(UserRepository.UnlinkOutcome.CannotUnlinkLast, repo.unlinkIdentity(a.id, AuthProvider.GOOGLE))
        assertNull(transaction {
            UserIdentitiesTable.selectAll().where { UserIdentitiesTable.accountId eq a.id }.firstOrNull()
        })
    }
}
