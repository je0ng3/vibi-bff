/**
 * 운영 대시보드 임계값 — Overview 헬스 카드와 잡 테이블의 색상 강조 기준.
 * 실데이터를 보고 조정할 것 (하드코딩 상수, 백엔드 무관).
 */

/** PROCESSING 상태가 이 시간을 넘으면 stuck 의심(빨강). 긴 영상 렌더 여유 포함 15분. */
export const STUCK_JOB_MS = 15 * 60_000;

/** 누적 잡 성공률(%) — 이 미만은 amber, ALERT 미만은 red. */
export const JOB_SUCCESS_WARN = 99;
export const JOB_SUCCESS_ALERT = 90;

/** 외부(Perso) 실패율(%) — 초과 시 amber, ALERT 초과 시 red. */
export const UPSTREAM_FAIL_WARN = 1;
export const UPSTREAM_FAIL_ALERT = 5;

/** 헬스 카드 시간창(시간). Overview 가 /admin/health 에 넘기는 값과 표시에 공용. */
export const HEALTH_WINDOW_HOURS = 24;

/**
 * 소표본 가드 — 이 미만이면 비율을 색으로 경고하지 않는다(값은 표시). 창 내 1/2 실패 같은
 * 통계적으로 무의미한 스파이크가 빨강으로 뜨는 노이즈 방지.
 */
export const HEALTH_MIN_JOBS = 5;
export const HEALTH_MIN_CALLS = 20;
