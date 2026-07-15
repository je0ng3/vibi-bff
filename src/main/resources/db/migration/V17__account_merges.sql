-- account_merges — 계정 통합(병합) 감사 로그 (append-only). account_deletions(V15) 와 대칭.
--
-- 다른 계정 B 를 현재 계정 A 로 흡수하는 병합([UserRepository.mergeAccounts])은 B 를 하드
-- 삭제하고 B 의 identity 를 A 의 secondary 로 편입한다. 병합 자체(어느 계정이·언제 A 로
-- 합쳐졌나 + 이월 크레딧)는 지금까지 SLF4J 로그 한 줄로만 남아 관리자 화면에서 볼 수 없었다.
-- 이 테이블에 병합 시점 1 row 를 적재해 사용자 상세 페이지가 "무슨 계정이 합쳐졌고 몇 크레딧이
-- 이월됐는지" 를 보여준다.
--
-- into_account_id 는 병합 후 살아남는 계정(A). users(id) FK + ON DELETE CASCADE — A 가 회원탈퇴
-- 하면 그 병합 이력도 함께 지워진다 (GDPR erasure 정합 + orphan 방지). from_email 은 흡수된
-- 계정 B 의 이메일 — 이미 user_identities 에 A 의 secondary 로 보존되는 값이라 신규 PII 아님.
CREATE TABLE account_merges (
    id              BIGSERIAL PRIMARY KEY,
    into_account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_provider   VARCHAR(16) NOT NULL,
    from_email      VARCHAR(320) NOT NULL,
    carried_credits INTEGER NOT NULL,
    merged_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 사용자 상세 페이지의 "이 계정으로 합쳐진 이력" 조회용 (into_account_id = 대상 계정).
CREATE INDEX account_merges_into_idx ON account_merges (into_account_id);
