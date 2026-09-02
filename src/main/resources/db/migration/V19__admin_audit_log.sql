-- admin_audit_log — 운영자 mutating 액션의 append-only 감사 로그.
--
-- 지금까지 admin 대시보드의 mutating 액션(role 변경, 재가입 차단 해제)은 SLF4J 로그 한 줄로만
-- 남아 Cloud Run 로그 보존 기간이 지나면 사라졌다. 여기에 크레딧 수동 지급이 추가되면서
-- "누가·누구에게·몇 개를·왜 지급했나" 를 DB 에 영구 보존할 필요가 생겼다 (내부 부정 사용
-- 추적 + 잔액 불일치 조사의 1차 자료).
--
-- 컬럼 의미는 action 별로 다르다:
--   • credit_grant   — target_user_id=수령자, amount=지급 크레딧, detail=운영자가 입력한 사유,
--                      credit_transaction_id=이 지급이 만든 credit_transactions row
--   • set_role       — target_user_id=대상, detail=새 role ('admin'|'user')
--   • unblock_rejoin — target_user_id NULL (탈퇴자라 users row 없음), detail=identity 해시
--
-- actor_email 은 조회 시 join 을 피하려 denormalize 한 값이다. FK 는 둘 다 ON DELETE SET NULL —
-- 사용자 탈퇴가 감사 row 를 지우지 않는다 (account_merges 의 CASCADE 와 반대인 이유: 저건 계정의
-- 속성, 이건 운영 행위 기록).
--
-- **삭제 정책의 명시적 예외**: UserRepository.delete 는 이메일이 남지 않도록 users row 를 하드
-- 삭제하는데, 여기 복사된 actor_email 은 그 뒤에도 남는다. 의도된 예외다 — 행위자를 지우면
-- "누가 크레딧을 넣었나" 를 답할 수 없어 감사 로그의 존재 이유가 사라진다. 대상 범위도 운영자
-- (admin role) 계정으로 한정되고 일반 사용자 이메일은 target_user_id 의 FK 로만 참조하므로
-- (탈퇴 시 NULL) 일반 사용자에겐 적용되지 않는다.
CREATE TABLE admin_audit_log (
    id                    BIGSERIAL PRIMARY KEY,
    actor_user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_email           VARCHAR(320) NOT NULL,
    action                VARCHAR(32) NOT NULL,
    target_user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    amount                INTEGER,
    detail                VARCHAR(500),
    credit_transaction_id BIGINT REFERENCES credit_transactions(id) ON DELETE SET NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 전체 감사 로그 페이지 (최신순).
CREATE INDEX admin_audit_log_created_idx ON admin_audit_log (created_at DESC);
-- 사용자 상세의 크레딧 타임라인이 admin_grant 이벤트에 사유·지급자를 붙일 때 쓰는 lookup.
CREATE INDEX admin_audit_log_tx_idx ON admin_audit_log (credit_transaction_id);
-- 지급자별 24h 상한 계산 (actor 기준 SUM).
CREATE INDEX admin_audit_log_actor_idx ON admin_audit_log (actor_user_id, created_at DESC);
