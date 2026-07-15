package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * account_merges — 계정 통합(병합) 감사/집계 로그 (append-only). V17__account_merges.sql 의
 * DDL 과 1:1. 병합 흐름([com.vibi.bff.service.UserRepository] 의 mergeAccounts)이 흡수된 계정 B 를
 * 삭제하기 직전 같은 트랜잭션에서 1 row insert.
 *
 * - [intoAccountId] — 병합 후 살아남는 계정(A). users(id) FK + ON DELETE CASCADE.
 * - [fromProvider] / [fromEmail] — 흡수돼 사라진 계정 B 의 provider + 이메일 ("무슨 계정이 합쳐졌나").
 * - [carriedCredits] — 이 병합으로 A 에 이월된 크레딧 (무료 보너스 제외분. 0 일 수 있음).
 */
object AccountMergesTable : LongIdTable("account_merges", "id") {
    val intoAccountId = uuid("into_account_id")
    val fromProvider = varchar("from_provider", 16)
    val fromEmail = varchar("from_email", 320)
    val carriedCredits = integer("carried_credits")
    val mergedAt = timestamp("merged_at")
}
