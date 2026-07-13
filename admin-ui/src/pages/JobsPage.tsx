import { adminFetch, AdminActiveJob, AdminJobStatusBreakdown } from "../lib/api";
import { useAdminData } from "../lib/useAdminData";
import { STUCK_JOB_MS } from "../lib/thresholds";
import { Loading, ErrorBanner, PageHeader } from "../components/StateViews";
import JobStatusTable from "../components/JobStatusTable";
import ActiveJobsTable from "../components/ActiveJobsTable";

interface JobsData {
  status: AdminJobStatusBreakdown[];
  active: AdminActiveJob[];
}

const REFRESH_MS = 30_000;

/** render / separation 잡 운영 — 성공·실패 분해 + 진행중(stuck 강조). 30초 자동 갱신. */
export default function JobsPage() {
  const { data, error, loading, lastLoadedAt, reload } = useAdminData<JobsData>(
    async () => {
      const [status, active] = await Promise.all([
        adminFetch<AdminJobStatusBreakdown[]>("/api/v2/admin/jobs/status-breakdown"),
        adminFetch<AdminActiveJob[]>("/api/v2/admin/jobs/active"),
      ]);
      return { status, active };
    },
    { refreshMs: REFRESH_MS },
  );

  if (error) return <ErrorBanner message={error} />;
  if (!data) return <Loading />;

  return (
    <div className="space-y-10">
      <PageHeader
        title="Jobs"
        hint={`render / separation 잡 · ${REFRESH_MS / 1000}초마다 자동 갱신`}
        lastLoadedAt={lastLoadedAt}
        loading={loading}
        onReload={reload}
      />

      <section className="space-y-3">
        <div>
          <h2 className="text-lg font-semibold">Success / failure</h2>
          <p className="mt-1 text-sm text-neutral-500">
            render / separation 의 성공·실패·진행중 분해. 성공률은 종료된 잡(성공+실패) 기준.
          </p>
        </div>
        <JobStatusTable rows={data.status} />
      </section>

      <section className="space-y-3">
        <div>
          <h2 className="text-lg font-semibold">In progress</h2>
          <p className="mt-1 text-sm text-neutral-500">
            현재 PROCESSING 상태인 잡. {STUCK_JOB_MS / 60000}분 이상이면 빨강으로 강조 — stuck 의심.
          </p>
        </div>
        <ActiveJobsTable rows={data.active} stuckMs={STUCK_JOB_MS} />
      </section>
    </div>
  );
}
