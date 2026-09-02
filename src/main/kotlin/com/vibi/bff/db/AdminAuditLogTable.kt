package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * 운영자 mutating 액션의 감사 로그 (V19). append-only — UPDATE/DELETE 하지 않는다.
 *
 * [action] 별로 [amount]/[detail]/[creditTransactionId] 의미가 다르다 (V19 마이그레이션 주석
 * + [com.vibi.bff.service.AdminRepository.recordAudit] 참조). [actorEmail] 은 운영자 계정이
 * 삭제돼도 "누가 했는지" 가 남도록 denormalize 한 값.
 */
object AdminAuditLogTable : LongIdTable("admin_audit_log", "id") {
    val actorUserId = uuid("actor_user_id").nullable()
    val actorEmail = varchar("actor_email", 320)
    val action = varchar("action", 32)
    val targetUserId = uuid("target_user_id").nullable()
    val amount = integer("amount").nullable()
    val detail = varchar("detail", 500).nullable()
    val creditTransactionId = long("credit_transaction_id").nullable()
    val createdAt = timestamp("created_at")
}
