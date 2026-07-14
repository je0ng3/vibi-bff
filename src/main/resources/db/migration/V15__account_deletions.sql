-- account_deletions — 회원탈퇴(계정 삭제) 감사/집계 로그 (append-only).
--
-- DELETE /auth/account 는 users row 를 하드 삭제(GDPR 17조 erasure)한다. 자식 잡·거래는
-- SET NULL 로 익명 보존되지만, "탈퇴 사건" 자체(몇 명이·언제·어느 provider 로 나갔나)는
-- 아무 곳에도 안 남는다. 이 테이블에 삭제 직전 1 row 를 적재해 이탈을 집계한다.
--
-- PII 를 저장하지 않는다 — email/이름/원 user UUID 없음. GDPR 삭제권과 충돌하지 않는
-- 통계 최소 필드만: provider + 가입시각(체류기간 계산용) + 삭제시각. FK 없음(원 user 는
-- 이 row 를 쓰는 시점에 이미 삭제 대상).
CREATE TABLE account_deletions (
    id           BIGSERIAL PRIMARY KEY,
    provider     VARCHAR(16) NOT NULL,
    signed_up_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 일별 집계 / 30일 카운트 range 쿼리용.
CREATE INDEX account_deletions_deleted_at_idx ON account_deletions (deleted_at);
