import {
  adminFetch,
  AdminActiveJob,
  AdminHealth,
  AdminOverview,
} from "../lib/api";
import { useAdminData } from "../lib/useAdminData";
import { elapsedMs } from "../lib/format";
import {
  STUCK_JOB_MS,
  JOB_SUCCESS_WARN,
  JOB_SUCCESS_ALERT,
  UPSTREAM_FAIL_WARN,
  UPSTREAM_FAIL_ALERT,
  HEALTH_WINDOW_HOURS,
  HEALTH_MIN_JOBS,
  HEALTH_MIN_CALLS,
} from "../lib/thresholds";
import { Loading, ErrorBanner, PageHeader, HealthCard } from "../components/StateViews";
import StatCard from "../components/StatCard";
import ActiveJobsTable from "../components/ActiveJobsTable";

interface OverviewData {
  overview: AdminOverview;
  active: AdminActiveJob[];
  health: AdminHealth;
}

const REFRESH_MS = 30_000;

/**
 * 관리자 첫 화면 = 운영 헬스 스냅샷. "지금 문제 있나?"에 먼저 답한다 —
 * 상단 헬스 카드(stuck 잡·진행중·최근 성공률·최근 업스트림 실패율)가 임계 초과 시 빨강/amber.
 * 성공률/실패율은 누적이 아닌 최근 시간창(/admin/health) 기준이라 급성 스파이크를 잡는다.
 * stuck 잡이 있으면 목록으로 펼치고, 누적 KPI 는 그 아래 보조 지표로. 30초 자동 갱신.
 */
export default function DashboardPage() {
  const { data, error, loading, lastLoadedAt, reload } = useAdminData<OverviewData>(
    async () => {
      const [overview, active, health] = await Promise.all([
        adminFetch<AdminOverview>("/api/v2/admin/overview"),
        adminFetch<AdminActiveJob[]>("/api/v2/admin/jobs/active"),
        adminFetch<AdminHealth>(`/api/v2/admin/health?hours=${HEALTH_WINDOW_HOURS}`),
      ]);
      return { overview, active, health };
    },
    { refreshMs: REFRESH_MS },
  );

  if (error) return <ErrorBanner message={error} />;
  if (!data) return <Loading />;

  const { overview: o, active, health } = data;
  const win = health.windowHours;

  // ── 헬스 계산 (최근 시간창 기준) ─────────────────────────────────────────
  const stuck = active.filter((j) => elapsedMs(j.createdAt) > STUCK_JOB_MS);

  // 잡 성공률 — 창 내 종료 잡(성공+실패). 소표본이면 색 경고 없이 값만.
  const successRate =
    health.jobsTerminal > 0 ? ((health.jobsTerminal - health.jobsFailed) / health.jobsTerminal) * 100 : null;
  const jobSample = health.jobsTerminal >= HEALTH_MIN_JOBS;

  // 업스트림 실패율 — 창 내 Perso 호출. 소표본 가드 동일.
  const failRate = health.upstreamCalls > 0 ? (health.upstreamFailures / health.upstreamCalls) * 100 : null;
  const callSample = health.upstreamCalls >= HEALTH_MIN_CALLS;

  return (
    <div className="space-y-8">
      <PageHeader
        title="Overview"
        hint={`핵심 헬스 지표 · 비율은 최근 ${win}h · ${REFRESH_MS / 1000}초마다 자동 갱신`}
        lastLoadedAt={lastLoadedAt}
        loading={loading}
        onReload={reload}
      />

      {/* 헬스 카드 — 임계 초과 시 색으로 즉시 경고 */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <HealthCard
          label="Stuck jobs"
          value={stuck.length}
          sub={`${STUCK_JOB_MS / 60000}분 이상 PROCESSING`}
          tone={stuck.length > 0 ? "alert" : "ok"}
        />
        <HealthCard
          label="In progress"
          value={active.length}
          sub="현재 PROCESSING 잡 수"
          tone="ok"
        />
        <HealthCard
          label={`Success rate (${win}h)`}
          value={successRate != null ? `${successRate.toFixed(1)}%` : "-"}
          sub={`최근 ${win}h 종료 잡 ${health.jobsTerminal.toLocaleString()}건${jobSample ? "" : " · 표본 부족"}`}
          tone={
            successRate == null || !jobSample
              ? "ok"
              : successRate < JOB_SUCCESS_ALERT
                ? "alert"
                : successRate < JOB_SUCCESS_WARN
                  ? "warn"
                  : "ok"
          }
        />
        <HealthCard
          label={`Perso failure (${win}h)`}
          value={failRate != null ? `${failRate.toFixed(1)}%` : "-"}
          sub={`p95 ${health.upstreamP95Ms.toLocaleString()}ms · ${health.upstreamCalls.toLocaleString()} calls${callSample ? "" : " · 표본 부족"}`}
          tone={
            failRate == null || !callSample
              ? "ok"
              : failRate > UPSTREAM_FAIL_ALERT
                ? "alert"
                : failRate > UPSTREAM_FAIL_WARN
                  ? "warn"
                  : "ok"
          }
        />
      </div>

      {/* stuck 잡이 있으면 즉시 조치용 목록 (없으면 숨김 — 노이즈 최소화) */}
      {stuck.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold text-rose-700">
            ⚠ Stuck jobs — {STUCK_JOB_MS / 60000}분 이상 진행 중
          </h2>
          <ActiveJobsTable rows={stuck} stuckMs={STUCK_JOB_MS} />
        </section>
      )}

      {/* 보조 누적 지표 */}
      <section className="space-y-4">
        <h2 className="text-sm font-medium text-neutral-500">누적 지표</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="rounded-lg border border-blue-200 bg-blue-50 p-6">
            <div className="text-xs font-medium uppercase tracking-wide text-blue-700">
              총 필요 크레딧
            </div>
            <div className="mt-2 text-4xl font-bold tabular-nums tracking-tight text-blue-900">
              {(o.totalUserCredits * 60 * 0.5).toLocaleString()}
            </div>
            <div className="mt-1 text-sm text-blue-700">
              전체 사용자 보유 크레딧 총합(분) {o.totalUserCredits.toLocaleString()} × 60 × 0.5
            </div>
          </div>
          {/* Perso 계정(space) 잔여 크레딧 — XP-API-KEY 기준. 외부 호출 실패 시 null → "조회 실패". */}
          <div className="rounded-lg border border-violet-200 bg-violet-50 p-6">
            <div className="text-xs font-medium uppercase tracking-wide text-violet-700">
              Perso 계정 잔여 크레딧
            </div>
            {o.persoAccountCredits != null ? (
              <div className="mt-2 text-4xl font-bold tabular-nums tracking-tight text-violet-900">
                {o.persoAccountCredits.toLocaleString()}
              </div>
            ) : (
              <div className="mt-2 text-2xl font-semibold text-violet-400">조회 실패</div>
            )}
            <div className="mt-1 text-sm text-violet-700">Perso API 기준 남은 quota</div>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <StatCard label="Users" value={o.totalUsers.toLocaleString()} />
          <StatCard label="Active (7d)" value={o.activeUsersLast7Days.toLocaleString()} />
          <StatCard
            label="Separations"
            value={o.totalSeparations.toLocaleString()}
            sub={`모바일 ${o.mobileSeparations.toLocaleString()} · 플러그인 ${o.pluginSeparations.toLocaleString()}`}
          />
        </div>
      </section>
    </div>
  );
}
