package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * deleted_identities — 탈퇴 identity 의 재가입 차단 tombstone. V18__deleted_identities.sql 과 1:1.
 *
 * PII 없음: "provider:provider_sub" 의 SHA-256 hex 해시만 저장. 탈퇴 흐름
 * ([com.vibi.bff.service.UserRepository.delete])이 primary + 링크된 secondary identity 전부를
 * upsert 하고, 가입 판정([com.vibi.bff.service.UserRepository.isBlockedRejoin])이
 * [com.vibi.bff.service.UserRepository.REJOIN_BLOCK] 창 안의 row 만 유효로 본다.
 */
object DeletedIdentitiesTable : Table("deleted_identities") {
    val identityHash = varchar("identity_hash", 64)

    /** "google"/"apple" — PII 아님. admin 차단 목록이 어떤 로그인 수단인지 보여주는 데 쓴다. */
    val provider = varchar("provider", 16)
    val deletedAt = timestamp("deleted_at")

    override val primaryKey = PrimaryKey(identityHash)
}
