package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * 운영자 mutating 액션의 감사 로그 (V19). append-only. 컬럼 의미는 [com.vibi.bff.model.AdminAuditEntry] 참조.
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
