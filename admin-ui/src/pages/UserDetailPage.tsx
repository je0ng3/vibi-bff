import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  adminFetch,
  AdminAuthError,
  AdminUserAccount,
  AdminUserJobsResponse,
} from "../lib/api";
import { formatDurationMs, formatIsoDateTime } from "../lib/format";
import { providerBadgeClass, providerLabel } from "../lib/providers";

const PAGE_SIZE = 50;

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const offset = Math.max(0, Number.parseInt(params.get("offset") ?? "0", 10) || 0);

  const [data, setData] = useState<AdminUserJobsResponse | null>(null);
  const [account, setAccount] = useState<AdminUserAccount | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await adminFetch<AdminUserJobsResponse>(
          `/api/v2/admin/users/${id}/jobs?limit=${PAGE_SIZE}&offset=${offset}`,
        );
        if (!cancelled) setData(res);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setError(e instanceof Error ? e.message : "load failed");
      }
    })();
    return () => { cancelled = true; };
  }, [id, offset, navigate]);

  // 계정 연결/병합 정보 — 페이지네이션(offset)과 무관하므로 별도 effect 로 한 번만 로드.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await adminFetch<AdminUserAccount>(`/api/v2/admin/users/${id}/account`);
        if (!cancelled) setAccount(res);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) navigate("/login", { replace: true });
        // 계정 정보 로드 실패는 잡 목록 표시를 막지 않는다 (best-effort 섹션).
      }
    })();
    return () => { cancelled = true; };
  }, [id, navigate]);

  if (!id) return null;
  if (error) return <p className="text-sm text-rose-600">{error}</p>;
  if (!data) return <p className="text-sm text-neutral-500">불러오는 중…</p>;

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const setOffset = (next: number) => setParams({ offset: String(Math.max(0, next)) });

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <Link to="/users" className="text-sm text-blue-600 hover:underline">← Users</Link>
        <h1 className="text-xl font-semibold">User · {id}</h1>
        <p className="text-sm text-neutral-500">Render 잡 + 영상 당 음원분리 사용 횟수. 최신순.</p>
      </header>

      {account && <AccountSection account={account} />}

      {data.jobs.length === 0 ? (
        <p className="rounded-lg border border-dashed border-neutral-300 p-8 text-center text-sm text-neutral-500">
          이 사용자의 render 잡이 아직 없습니다.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
          <table className="min-w-full divide-y divide-neutral-200 text-sm">
            <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
              <tr>
                <th className="px-4 py-3">Job ID</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3 text-right">Duration</th>
                <th className="px-4 py-3 text-right">Separations</th>
                <th className="px-4 py-3">Created</th>
                <th className="px-4 py-3">Finished</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {data.jobs.map((j) => (
                <tr key={j.jobId} className="hover:bg-neutral-50">
                  <td className="px-4 py-3 font-mono text-xs text-neutral-700">{j.jobId}</td>
                  <td className="px-4 py-3"><StatusBadge status={j.status} /></td>
                  <td className="px-4 py-3 text-right tabular-nums">{formatDurationMs(j.sourceDurationMs)}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{j.separationCount}</td>
                  <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(j.createdAt)}</td>
                  <td className="px-4 py-3 text-neutral-600">{j.finishedAt ? formatIsoDateTime(j.finishedAt) : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <nav className="flex items-center justify-between text-sm">
          <div className="text-neutral-500">Page {currentPage} / {totalPages}</div>
          <div className="flex gap-2">
            {offset > 0 && (
              <button onClick={() => setOffset(offset - PAGE_SIZE)} className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100">Prev</button>
            )}
            {currentPage < totalPages && (
              <button onClick={() => setOffset(offset + PAGE_SIZE)} className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100">Next</button>
            )}
          </div>
        </nav>
      )}
    </div>
  );
}

// 계정 연결 상태 + 병합 이력. 통합(병합) 안 한 계정은 연결 수단 1개 + 병합 이력 0건.
function AccountSection({ account }: { account: AdminUserAccount }) {
  const { identities, merges } = account;
  return (
    <section className="space-y-4 rounded-lg border border-neutral-200 bg-white p-4">
      <div className="space-y-2">
        <h2 className="text-sm font-semibold text-neutral-800">연결된 로그인 계정</h2>
        {identities.length === 0 ? (
          <p className="text-sm text-neutral-500">연결된 로그인 수단 정보가 없습니다.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {identities.map((idn) => (
              <li
                key={`${idn.provider}:${idn.email}`}
                className="flex items-center gap-2 rounded border border-neutral-200 bg-neutral-50 px-2.5 py-1.5 text-sm"
              >
                <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${providerBadgeClass(idn.provider)}`}>
                  {providerLabel(idn.provider)}
                </span>
                <span className="text-neutral-700">{idn.email}</span>
                <span className="text-xs text-neutral-400">{idn.primary ? "primary" : "linked"}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {merges.length > 0 && (
        <div className="space-y-2">
          <h2 className="text-sm font-semibold text-neutral-800">병합 이력</h2>
          <p className="text-xs text-neutral-500">이 계정으로 흡수된 다른 계정 + 이월된 크레딧.</p>
          <div className="overflow-x-auto rounded border border-neutral-200">
            <table className="min-w-full divide-y divide-neutral-200 text-sm">
              <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
                <tr>
                  <th className="px-4 py-2">흡수된 계정</th>
                  <th className="px-4 py-2 text-right">이월 크레딧</th>
                  <th className="px-4 py-2">병합 시각</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {merges.map((m, i) => (
                  <tr key={`${m.fromProvider}:${m.fromEmail}:${i}`} className="hover:bg-neutral-50">
                    <td className="px-4 py-2">
                      <span className="flex items-center gap-2">
                        <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${providerBadgeClass(m.fromProvider)}`}>
                          {providerLabel(m.fromProvider)}
                        </span>
                        <span className="text-neutral-700">{m.fromEmail}</span>
                      </span>
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {m.carriedCredits > 0 ? `+${m.carriedCredits}` : "0"}
                    </td>
                    <td className="px-4 py-2 text-neutral-600">{formatIsoDateTime(m.mergedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}

function StatusBadge({ status }: { status: string }) {
  const styles =
    status === "COMPLETED" ? "bg-emerald-100 text-emerald-800" :
    status === "FAILED" ? "bg-rose-100 text-rose-800" :
    "bg-neutral-100 text-neutral-700";
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ${styles}`}>{status}</span>;
}
