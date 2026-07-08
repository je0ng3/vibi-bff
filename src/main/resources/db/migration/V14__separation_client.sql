-- separation_jobs 에 제출 클라이언트('mobile'/'plugin') 컬럼 추가 — admin 대시보드가
-- 플러그인/모바일 사용량을 분리 집계할 수 있게 한다.
--
-- 값의 출처는 JWT 의 client claim (AuthService.issueAccessToken):
--   - device-code 로그인(UXP 패널 전용 경로) → 'plugin'
--   - 모바일 네이티브 ID Token 교환         → 'mobile'
-- 렌더는 모바일만 수행하므로 render_jobs 에는 컬럼을 추가하지 않는다.
ALTER TABLE separation_jobs ADD COLUMN IF NOT EXISTS client TEXT NOT NULL DEFAULT 'mobile';

-- 기존 row 백필 — client claim 도입 전 데이터는 V12 의 플러그인 history 메타
-- (project_id/file_name/byte_length, plugin 제출 경로만 채움) 보유 여부로 판별.
UPDATE separation_jobs
SET client = 'plugin'
WHERE project_id IS NOT NULL OR file_name IS NOT NULL OR byte_length IS NOT NULL;
