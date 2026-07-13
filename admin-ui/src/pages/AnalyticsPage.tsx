import {
  adminFetch,
  AdminAdStats,
  AdminDailyStats,
  AdminDeletionDaily,
  AdminDeletionStats,
  AdminDurationBucket,
  AdminExternalCallDaily,
  AdminSignupDaily,
} from "../lib/api";
import { useAdminData } from "../lib/useAdminData";
import { formatTenureDays } from "../lib/format";
import { Loading, ErrorBanner, PageHeader } from "../components/StateViews";
import StatCard from "../components/StatCard";
import DailyChart from "../components/DailyChart";
import HistogramChart from "../components/HistogramChart";
import SignupChart from "../components/SignupChart";
import DeletionChart from "../components/DeletionChart";
import ExternalCallsTable from "../components/ExternalCallsTable";

interface AnalyticsData {
  ads: AdminAdStats;
  daily: AdminDailyStats[];
  histogram: AdminDurationBucket[];
  signups: AdminSignupDaily[];
  deletions: AdminDeletionDaily[];
  deletionStats: AdminDeletionStats;
  external: AdminExternalCallDaily[];
}

/**
 * 추이·집계 분석 통합 페이지 — 광고 / 일별 잡 / 영상 길이 분포 / 신규 가입 / 외부 API.
 * 저빈도(하루 단위 변화) 지표라 Overview 헬스와 분리해 한 페이지에 섹션으로 모았다.
 */
export default function AnalyticsPage() {
  const { data, error, loading, lastLoadedAt, reload } = useAdminData<AnalyticsData>(async () => {
    const [ads, daily, histogram, signups, deletions, deletionStats, external] = await Promise.all([
      adminFetch<AdminAdStats>("/api/v2/admin/ads"),
      adminFetch<AdminDailyStats[]>("/api/v2/admin/stats/daily"),
      adminFetch<AdminDurationBucket[]>("/api/v2/admin/stats/duration-histogram"),
      adminFetch<AdminSignupDaily[]>("/api/v2/admin/stats/signups"),
      adminFetch<AdminDeletionDaily[]>("/api/v2/admin/stats/deletions"),
      adminFetch<AdminDeletionStats>("/api/v2/admin/deletions"),
      adminFetch<AdminExternalCallDaily[]>("/api/v2/admin/stats/external-calls"),
    ]);
    return { ads, daily, histogram, signups, deletions, deletionStats, external };
  });

  if (error) return <ErrorBanner message={error} />;
  if (!data) return <Loading />;

  return (
    <div className="space-y-10">
      <PageHeader
        title="Analytics"
        hint="추이·집계 지표. UTC 기준."
        lastLoadedAt={lastLoadedAt}
        loading={loading}
        onReload={reload}
      />

      <section className="space-y-4">
        <SectionTitle title="Ad watches" hint="보상형 광고(AdMob) 시청 완료 횟수. 1회 = 1 크레딧." />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <StatCard label="Total ad watches" value={data.ads.totalWatches.toLocaleString()} sub="누적" />
          <StatCard label="Ad watches (30d)" value={data.ads.watches30d.toLocaleString()} sub="최근 30일" />
          <StatCard label="Watching users" value={data.ads.watchingUsers.toLocaleString()} sub="1회 이상 시청" />
        </div>
      </section>

      <section className="space-y-4">
        <SectionTitle
          title="Last 30 days"
          hint="일별 render / separation 잡 카운트. separation 은 모바일·플러그인 스택."
        />
        <div className="rounded-lg border border-neutral-200 bg-white p-4">
          <DailyChart data={data.daily} />
        </div>
      </section>

      <section className="space-y-4">
        <SectionTitle title="Video duration distribution" hint="render 잡 입력 영상 길이 분포." />
        <div className="rounded-lg border border-neutral-200 bg-white p-4">
          <HistogramChart data={data.histogram} />
        </div>
      </section>

      <section className="space-y-4">
        <SectionTitle
          title="Signups vs. churn (30d)"
          hint="일별 신규 가입 대비 회원탈퇴. 탈퇴 기록은 V15 배포 이후분만 집계됨."
        />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <StatCard label="Total deletions" value={data.deletionStats.totalDeletions.toLocaleString()} sub="누적 탈퇴" />
          <StatCard label="Deletions (30d)" value={data.deletionStats.deletions30d.toLocaleString()} sub="최근 30일" />
          <StatCard label="Avg tenure" value={formatTenureDays(data.deletionStats.avgTenureDays)} sub="가입~탈퇴 평균" />
          <StatCard label="Median tenure" value={formatTenureDays(data.deletionStats.medianTenureDays)} sub="가입~탈퇴 중앙값" />
        </div>
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="space-y-2">
            <div className="text-sm font-medium text-neutral-600">New signups</div>
            <div className="rounded-lg border border-neutral-200 bg-white p-4">
              <SignupChart data={data.signups} />
            </div>
          </div>
          <div className="space-y-2">
            <div className="text-sm font-medium text-neutral-600">Account deletions</div>
            <div className="rounded-lg border border-neutral-200 bg-white p-4">
              <DeletionChart data={data.deletions} />
            </div>
          </div>
        </div>
      </section>

      <section className="space-y-4">
        <SectionTitle
          title="External API calls"
          hint="Perso 호출 카운트 + 실패율 + p95 latency. 비용 추정 + 안정성 모니터."
        />
        <ExternalCallsTable rows={data.external} />
      </section>
    </div>
  );
}

function SectionTitle({ title, hint }: { title: string; hint: string }) {
  return (
    <div>
      <h2 className="text-lg font-semibold">{title}</h2>
      <p className="mt-1 text-sm text-neutral-500">{hint}</p>
    </div>
  );
}
