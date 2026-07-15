-- user_identities — 계정(users)에 추가로 링크된 소셜 로그인 수단(secondary identity).
--
-- 배경: V1 이래 계정 식별 키는 users.(provider, provider_sub) 였다 — 같은 사람이 Google·Apple
-- 로 각각 로그인하면 별개 계정 2개가 생겼다. 명시적 계정 통합(로그인 후 '다른 provider 연결')을
-- 위해, 계정(users)과 로그인 수단을 분리한다. 단 마이그레이션 리스크를 낮추려고 users 는 그대로
-- 두고(= 계정 + primary identity), 본 테이블은 **추가 링크된 secondary identity 만** 담는다.
--   • 신규 가입 / 재로그인(핫패스) = users 의 원자적 upsert 그대로 (백필·경로 변경 없음).
--   • 계정 A 에 두 번째 provider B 연결 = user_identities 에 (B.provider, B.sub → account_id=A) 1 row.
--   • 인증 조회 = users(primary) 먼저, 없으면 user_identities(secondary) → account_id 로 resolve.
--
-- (provider, provider_sub) 를 PK 로 둬 한 identity 가 두 계정에 붙지 못하게 강제한다
-- (users.(provider, provider_sub) 와의 교차 중복은 앱 로직 UserRepository 가 findAccountByIdentity
-- 로 사전 차단 — DB 레벨 교차 UNIQUE 는 두 테이블에 걸 수 없어 애플리케이션 불변식으로 유지).
--
-- account_id 는 users(id) FK + ON DELETE CASCADE — 회원탈퇴 시 링크된 identity 도 함께 삭제.
-- provider CHECK 는 UsersTable / AuthProvider.dbValue 와 동기 — 새 provider 추가 시 셋 다 갱신.
CREATE TABLE user_identities (
    account_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider     VARCHAR(16) NOT NULL,
    provider_sub VARCHAR(255) NOT NULL,
    email        VARCHAR(320) NOT NULL,
    name         VARCHAR(255),
    picture      VARCHAR(2048),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider, provider_sub),
    CONSTRAINT user_identities_provider_check CHECK (provider IN ('google', 'apple'))
);

-- account_id 로 "이 계정에 링크된 provider 목록" 조회 (GET /auth/identities, 병합, 언링크).
CREATE INDEX user_identities_account_idx ON user_identities (account_id);
-- email 로 향후 통합 후보 탐색 (users_email_idx 와 대칭).
CREATE INDEX user_identities_email_idx ON user_identities (email);
