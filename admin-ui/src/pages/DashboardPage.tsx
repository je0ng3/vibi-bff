import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  adminFetch,
  AdminAuthError,
  AdminActiveJob,
  AdminAdStats,
  AdminDailyStats,
  AdminDurationBucket,
  AdminExternalCallDaily,
  AdminJobStatusBreakdown,
  AdminOverview,
  AdminSignupDaily,
} from "../lib/api";
import { formatDurationMs } from "../lib/format";
import StatCard from "../components/StatCard";
import DailyChart from "../components/DailyChart";
import HistogramChart from "../components/HistogramChart";
import ExternalCallsTable from "../components/ExternalCallsTable";
import SignupChart from "../components/SignupChart";
import ActiveJobsTable from "../components/ActiveJobsTable";
import JobStatusTable from "../components/JobStatusTable";

interface State {
  overview: AdminOverview | null;
  ads: AdminAdStats | null;
  jobStatus: AdminJobStatusBreakdown[];
  daily: AdminDailyStats[];
  externalCalls: AdminExternalCallDaily[];
  histogram: AdminDurationBucket[];
  activeJobs: AdminActiveJob[];
  signups: AdminSignupDaily[];
  loading: boolean;
  error: string | null;
}

const INITIAL: State = {
  overview: null, ads: null, jobStatus: [],
  daily: [], externalCalls: [], histogram: [], activeJobs: [], signups: [],
  loading: true, error: null,
};

export default function DashboardPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<State>(INITIAL);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [overview, ads, jobStatus, daily, externalCalls, histogram, activeJobs, signups] =
          await Promise.all([
            adminFetch<AdminOverview>("/api/v2/admin/overview"),
            adminFetch<AdminAdStats>("/api/v2/admin/ads"),
            adminFetch<AdminJobStatusBreakdown[]>("/api/v2/admin/jobs/status-breakdown"),
            adminFetch<AdminDailyStats[]>("/api/v2/admin/stats/daily"),
            adminFetch<AdminExternalCallDaily[]>("/api/v2/admin/stats/external-calls"),
            adminFetch<AdminDurationBucket[]>("/api/v2/admin/stats/duration-histogram"),
            adminFetch<AdminActiveJob[]>("/api/v2/admin/jobs/active"),
            adminFetch<AdminSignupDaily[]>("/api/v2/admin/stats/signups"),
          ]);
        if (!cancelled) setState({
          overview, ads, jobStatus,
          daily, externalCalls, histogram, activeJobs, signups,
          loading: false, error: null,
        });
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setState((s) => ({ ...s, loading: false, error: e instanceof Error ? e.message : "load failed" }));
      }
    })();
    return () => { cancelled = true; };
  }, [navigate]);

  if (state.loading) return <p className="text-sm text-neutral-500">불러오는 중…</p>;
  if (state.error) return <ErrorBanner message={state.error} />;
  if (!state.overview) return null;

  const o = state.overview;
  return (
    <div className="space-y-10">
      <section>
        <h1 className="text-xl font-semibold">Overview</h1>
        <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 p-6">
          <div className="text-xs font-medium uppercase tracking-wide text-blue-700">
            전체 사용자 보유 크레딧 총합
          </div>
          <div className="mt-2 text-4xl font-bold tabular-nums tracking-tight text-blue-900">
            {o.totalUserCredits.toLocaleString()}
          </div>
          <div className="mt-1 text-sm text-blue-700">
            × 60 = {(o.totalUserCredits * 60).toLocaleString()}
          </div>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-4">
          <StatCard label="Users" value={o.totalUsers.toLocaleString()} />
          <StatCard label="Active (7d)" value={o.activeUsersLast7Days.toLocaleString()} />
          <StatCard label="Renders" value={o.totalRenders.toLocaleString()} />
          <StatCard
            label="Separations"
            value={o.totalSeparations.toLocaleString()}
            sub={`모바일 ${o.mobileSeparations.toLocaleString()} · 플러그인 ${o.pluginSeparations.toLocaleString()}`}
          />
        </div>
        <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
          <StatCard
            label="Avg separation length"
            value={formatDurationMs(o.avgSeparationDurationMs)}
            sub="음원분리 잡 1건당 평균 입력 길이 (전체)"
          />
          <StatCard
            label="Avg separation · mobile"
            value={formatDurationMs(o.avgMobileSeparationDurationMs)}
            sub="모바일 앱 분리 잡 평균"
          />
          <StatCard
            label="Avg separation · plugin"
            value={formatDurationMs(o.avgPluginSeparationDurationMs)}
            sub="Adobe 플러그인 분리 잡 평균"
          />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Ad watches</h2>
        <p className="mt-1 text-sm text-neutral-500">
          보상형 광고(AdMob) 시청 완료 횟수. 1회 시청 = 1 크레딧 지급. 결제 통계는 제거됨.
        </p>
        <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3">
          <StatCard
            label="Total ad watches"
            value={(state.ads?.totalWatches ?? 0).toLocaleString()}
            sub="누적 광고 시청 횟수"
          />
          <StatCard
            label="Ad watches (30d)"
            value={(state.ads?.watches30d ?? 0).toLocaleString()}
            sub="최근 30일"
          />
          <StatCard
            label="Watching users"
            value={(state.ads?.watchingUsers ?? 0).toLocaleString()}
            sub="광고 1회 이상 시청 사용자"
          />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Job success / failure</h2>
        <p className="mt-1 text-sm text-neutral-500">
          render / separation 의 성공·실패·진행중 분해. 성공률은 종료된 잡(성공+실패) 기준.
        </p>
        <div className="mt-4">
          <JobStatusTable rows={state.jobStatus} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">In progress</h2>
        <p className="mt-1 text-sm text-neutral-500">
          현재 PROCESSING 상태인 잡. 오래 머물러 있으면 stuck 의심.
        </p>
        <div className="mt-4">
          <ActiveJobsTable rows={state.activeJobs} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Last 30 days</h2>
        <p className="mt-1 text-sm text-neutral-500">
          일별 render / separation 잡 카운트. separation 은 모바일·플러그인 스택. UTC 기준.
        </p>
        <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
          <DailyChart data={state.daily} />
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section>
          <h2 className="text-lg font-semibold">Video duration distribution</h2>
          <p className="mt-1 text-sm text-neutral-500">
            사용자가 올린 render 잡의 입력 영상 길이 분포.
          </p>
          <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
            <HistogramChart data={state.histogram} />
          </div>
        </section>

        <section>
          <h2 className="text-lg font-semibold">New signups (30d)</h2>
          <p className="mt-1 text-sm text-neutral-500">
            일별 신규 가입자 + provider (Google / Apple) 비중.
          </p>
          <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
            <SignupChart data={state.signups} />
          </div>
        </section>
      </div>

      <section>
        <h2 className="text-lg font-semibold">External API calls</h2>
        <p className="mt-1 text-sm text-neutral-500">
          Perso 호출 카운트 + 실패율 + p95 latency. 비용 추정 + 안정성 모니터.
        </p>
        <div className="mt-4">
          <ExternalCallsTable rows={state.externalCalls} />
        </div>
      </section>
    </div>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">
      {message}
    </div>
  );
}
