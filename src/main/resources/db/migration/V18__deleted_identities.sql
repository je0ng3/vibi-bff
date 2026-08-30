-- deleted_identities — 탈퇴한 로그인 identity 의 재가입 차단 tombstone.
--
-- 회원탈퇴 후 30일 내 같은 identity(구글/애플 계정)의 재가입을 막는다 — 탈퇴→재가입 반복으로
-- 가입 보너스 크레딧을 반복 수령하는 abuse 차단. PII 를 저장하지 않는다: "provider:provider_sub"
-- 의 SHA-256 해시만 보관해 원 identity 로 역산 불가 (account_deletions 와 같은 무PII 원칙,
-- GDPR 삭제권과 충돌 없는 판정 전용 최소 데이터). 같은 identity 재탈퇴 시 upsert 로 deleted_at 갱신.
-- 차단 창이 지난 row 는 조회에서 무시되고, 탈퇴 트랜잭션마다 기회적으로 정리된다.
-- provider 는 PII 가 아니며(account_deletions 와 동일 수준) admin 차단 목록에서 어떤 로그인
-- 수단이 막혔는지 식별해 해제 판단을 돕는다. 이게 없으면 해시만 나열돼 운영자가 손을 못 쓴다.
CREATE TABLE deleted_identities (
    identity_hash VARCHAR(64) PRIMARY KEY,
    provider      VARCHAR(16) NOT NULL,
    deleted_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 차단 창 판정 + 만료 row 정리 range 쿼리용.
CREATE INDEX deleted_identities_deleted_at_idx ON deleted_identities (deleted_at);
