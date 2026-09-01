import { loadAuth, clearAuth } from "./auth";

/**
 * BFF API fetch wrapper. Authorization 헤더 자동 부착. 401/403 시 토큰 비우고 throw —
 * caller (대부분 ProtectedRoute) 가 로그인 페이지로 redirect.
 *
 * baseUrl 은 same-origin (BFF 가 admin UI 서빙) 이라 빈 prefix. dev 모드 (vite dev server)
 * 에서 BFF 가 다른 origin 일 때는 VITE_BFF_BASE_URL 로 override.
 */
const BASE_URL = (import.meta.env.VITE_BFF_BASE_URL ?? "").replace(/\/+$/, "");

export class AdminAuthError extends Error {
  constructor(public readonly code: "missing_token" | "unauthorized" | "forbidden") {
    super(code);
    this.name = "AdminAuthError";
  }
}

export async function adminFetch<T>(path: string): Promise<T> {
  const auth = loadAuth();
  if (!auth) throw new AdminAuthError("missing_token");

  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    cache: "no-store",
  });
  if (res.status === 401) {
    clearAuth();
    throw new AdminAuthError("unauthorized");
  }
  if (res.status === 403) {
    throw new AdminAuthError("forbidden");
  }
  if (!res.ok) {
    throw new Error(`BFF ${path} returned ${res.status}`);
  }
  return (await res.json()) as T;
}

/**
 * mutating admin 액션용 POST. adminFetch 와 동일한 인증/에러 규약 — 401 은 토큰 비우고
 * AdminAuthError, 403 은 AdminAuthError(forbidden), 그 외 비 2xx 는 Error.
 */
export async function adminPost<T>(path: string, body: unknown): Promise<T> {
  const auth = loadAuth();
  if (!auth) throw new AdminAuthError("missing_token");

  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${auth.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (res.status === 401) {
    clearAuth();
    throw new AdminAuthError("unauthorized");
  }
  if (res.status === 403) {
    throw new AdminAuthError("forbidden");
  }
  if (!res.ok) {
    throw new Error(`BFF ${path} returned ${res.status}`);
  }
  return (await res.json()) as T;
}

/** Google ID Token → BFF JWT 교환. 응답 그대로 — caller 가 role 확인 후 저장. */
export async function exchangeGoogleIdToken(idToken: string): Promise<{
  accessToken: string;
  expiresAt: number;
  user: { sub: string; email: string; name: string; role?: string };
}> {
  const res = await fetch(`${BASE_URL}/api/v2/auth/google`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }),
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`google exchange failed (${res.status})`);
  }
  return res.json();
}

// ── 응답 DTO (BFF AdminModels.kt 와 1:1) ───────────────────────────────────
export interface AdminOverview {
  totalUsers: number;
  totalRenders: number;
  totalSeparations: number;
  mobileSeparations: number;
  pluginSeparations: number;
  totalSourceDurationMs: number;
  activeUsersLast7Days: number;
  /** 전체 사용자가 현재 보유한 크레딧 잔액 합계 (소비하면 줄어든다). */
  totalUserCredits: number;
  /** Perso 계정(space)의 남은 quota = 잔여 크레딧. 외부 호출 실패 시 null. */
  persoAccountCredits: number | null;
  /** 음원분리 잡 1건당 평균 입력 길이 ms — 전체 + 클라이언트별. 잡이 없으면 0. */
  avgSeparationDurationMs: number;
  avgMobileSeparationDurationMs: number;
  avgPluginSeparationDurationMs: number;
}

export interface AdminDailyStats {
  date: string;
  renderCount: number;
  separationCount: number;
  mobileSeparationCount: number;
  pluginSeparationCount: number;
  totalSourceDurationMs: number;
}

export interface AdminUserOverview {
  userId: string;
  email: string;
  name: string;
  role: string;
  totalRenders: number;
  totalSeparations: number;
  mobileSeparations: number;
  pluginSeparations: number;
  totalSourceDurationMs: number;
  lastActivityAt: string;
  /** 이 계정에 연결된 로그인 provider ('google'|'apple'). 계정 통합 안 했으면 1개. */
  linkedProviders: string[];
}

export interface AdminUsersResponse {
  users: AdminUserOverview[];
  total: number;
}

export interface AdminUserJob {
  jobId: string;
  status: string;
  sourceDurationMs: number;
  createdAt: string;
  finishedAt: string | null;
  separationCount: number;
}

export interface AdminUserJobsResponse {
  jobs: AdminUserJob[];
  total: number;
}

/**
 * 계정에 연결된 로그인 수단 한 건. primary=최초 가입 provider, false=링크된 secondary.
 * BFF 는 사용자 대면 GET /auth/identities 와 동일한 LinkedIdentity 를 그대로 재사용한다 (동일 JSON).
 */
export interface AdminLinkedIdentity {
  provider: string;
  email: string;
  primary: boolean;
}

/** 이 계정으로 흡수된 병합 1건. carriedCredits=이월 크레딧(무료 보너스 제외분, 0 가능). */
export interface AdminAccountMerge {
  fromProvider: string;
  fromEmail: string;
  carriedCredits: number;
  mergedAt: string;
}

/** 사용자 상세 페이지의 계정 연결/병합 정보. */
export interface AdminUserAccount {
  identities: AdminLinkedIdentity[];
  merges: AdminAccountMerge[];
}

/**
 * 크레딧 변동 1건. type 은 'signup' | 'purchase' | 'ad_reward' | 'admin_grant' |
 * 'separation' | 'refund' | 'merge_carry'. delta 는 부호 포함 (분리 차감만 음수).
 * jobId/sourceDurationMs 는 separation·refund 에서만 채워진다 (잡 row 가 남아있을 때).
 */
export interface AdminCreditEvent {
  at: string;
  type: string;
  delta: number;
  detail: string | null;
  jobId: string | null;
  sourceDurationMs: number | null;
}

/**
 * 크레딧 타임라인 응답. balance 는 user_credits 의 실제 잔액이고, hasMerges 가 true 면
 * 이벤트 delta 합계와 일치하지 않을 수 있다 — 병합으로 흡수된 계정의 결제 이력이 감사 보존을
 * 위해 re-point 되지만 잔액에 더해진 건 이월분뿐이기 때문 (BFF AdminUserCreditsResponse KDoc).
 */
export interface AdminUserCreditsResponse {
  balance: number;
  events: AdminCreditEvent[];
  total: number;
  hasMerges: boolean;
}

export interface AdminExternalCallDaily {
  date: string;
  provider: string;
  endpoint: string;
  callCount: number;
  failureCount: number;
  p95LatencyMs: number;
}

export interface AdminDurationBucket {
  bucket: string;
  count: number;
}

export interface AdminActiveJob {
  jobType: string;
  jobId: string;
  userEmail: string;
  sourceDurationMs: number;
  createdAt: string;
  /** 제출 클라이언트 — separation 은 'mobile'/'plugin', render 는 항상 'mobile'. */
  client: string;
}

export interface AdminSignupDaily {
  date: string;
  googleCount: number;
  appleCount: number;
}

export interface AdminAdStats {
  totalWatches: number;
  watches30d: number;
  watchingUsers: number;
}

export interface AdminDeletionStats {
  totalDeletions: number;
  deletions30d: number;
  avgTenureDays: number;
  medianTenureDays: number;
}

export interface AdminHealth {
  windowHours: number;
  jobsTerminal: number;
  jobsFailed: number;
  upstreamCalls: number;
  upstreamFailures: number;
  upstreamP95Ms: number;
}

export interface AdminDeletionDaily {
  date: string;
  googleCount: number;
  appleCount: number;
}

export interface AdminJobStatusBreakdown {
  jobType: string;
  /** separation 행은 'mobile'/'plugin' 으로 분리, render 행은 null (모바일 전용). */
  client: string | null;
  total: number;
  succeeded: number;
  failed: number;
  inProgress: number;
}

/**
 * 탈퇴 후 재가입이 차단된 identity 1건. PII 없음 — 운영자는 provider + 탈퇴시각으로 대상을
 * 특정한다 (서버가 해시만 보관하므로 이메일/이름을 줄 수 없다).
 */
export interface AdminBlockedRejoin {
  identityHash: string;
  provider: string;
  deletedAt: string;
  blockedUntil: string;
}

export interface AdminBlockedRejoinsResponse {
  blocked: AdminBlockedRejoin[];
}

export interface AdminUnblockRejoinResponse {
  unblocked: boolean;
}
