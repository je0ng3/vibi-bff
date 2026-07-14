package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * user_identities — 계정(users)에 추가로 링크된 secondary 로그인 수단. V16 마이그레이션과 1:1.
 *
 * users 는 계정 + primary identity 를 그대로 유지하고, 본 테이블은 계정 통합('다른 provider 연결')
 * 으로 붙은 두 번째 identity 만 담는다. 인증 조회는 users(primary) → user_identities(secondary) 순
 * (UserRepository.findAccountByIdentity). `(provider, providerSub)` 가 PK — 한 identity 는 한 계정에만.
 *
 * accountId 는 users(id) FK + ON DELETE CASCADE (회원탈퇴 시 함께 삭제).
 */
object UserIdentitiesTable : Table("user_identities") {
    val accountId = uuid("account_id")
    val provider = varchar("provider", 16)
    val providerSub = varchar("provider_sub", 255)
    val email = varchar("email", 320)
    val name = varchar("name", 255).nullable()
    val picture = varchar("picture", 2048).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(provider, providerSub)
}
