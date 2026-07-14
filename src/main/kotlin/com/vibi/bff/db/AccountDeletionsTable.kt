package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * account_deletions — 회원탈퇴 감사/집계 로그 (append-only). V15__account_deletions.sql 의
 * DDL 과 1:1. PII 없음: provider + 가입시각 + 삭제시각만. 삭제 흐름([com.vibi.bff.service
 * .UserRepository.delete])이 users 하드 삭제 직전 같은 트랜잭션에서 1 row insert.
 */
object AccountDeletionsTable : LongIdTable("account_deletions", "id") {
    val provider = varchar("provider", 16)
    val signedUpAt = timestamp("signed_up_at")
    val deletedAt = timestamp("deleted_at")
}
