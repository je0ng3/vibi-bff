import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  adminFetch,
  AdminAuthError,
  AdminCreditEvent,
  AdminUserAccount,
  AdminUserCreditsResponse,
  AdminUserJobsResponse,
} from "../lib/api";
import { formatDurationMs, formatIsoDateTime } from "../lib/format";
import { providerBadgeClass, providerLabel } from "../lib/providers";

const PAGE_SIZE = 50;
const CREDITS_PAGE_SIZE = 50;

// 크레딧 이벤트 type → 표시 라벨/배지. BFF AdminCreditEvent.type 과 1:1.
const CREDIT_EVENT_LABEL: Record<string, string> = {
  signup: "가입 보너스",
  purchase: "인앱 구매",
  ad_reward: "광고 시청",
  admin_grant: "관리자 지급",
  separation: "음원 분리",
  refund: "실패 환불",
  merge_carry: "계정 병합 이월",
};

const CREDIT_EVENT_BADGE: Record<string, string> = {
  signup: "bg-sky-100 text-sky-800",
  purchase: "bg-emerald-100 text-emerald-800",
  ad_reward: "bg-amber-100 text-amber-800",
  admin_grant: "bg-violet-100 text-violet-800",
  separation: "bg-neutral-100 text-neutral-700",
  refund: "bg-blue-100 text-blue-800",
  merge_carry: "bg-teal-100 text-teal-800",
};

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

      <CreditsSection userId={id} />

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

// 크레딧 변동 타임라인 — 지급(가입 보너스/구매/광고/관리자)·차감(분리)·환불·병합 이월을
// 한 스트림으로. 최신순이며 "더 보기" 로 이어붙인다 (잡 목록의 페이지 이동과 달리 누적 열람이
// 자연스러운 이력 화면이라).
function CreditsSection({ userId }: { userId: string }) {
  const [summary, setSummary] = useState<AdminUserCreditsResponse | null>(null);
  const [events, setEvents] = useState<AdminCreditEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = async (offset: number) => {
    setLoading(true);
    try {
      const res = await adminFetch<AdminUserCreditsResponse>(
        `/api/v2/admin/users/${userId}/credits?limit=${CREDITS_PAGE_SIZE}&offset=${offset}`,
      );
      setSummary(res);
      setEvents((prev) => (offset === 0 ? res.events : [...prev, ...res.events]));
    } catch {
      // 잡 목록 표시를 막지 않는 best-effort 섹션 — 인증 만료는 다른 요청이 로그인으로 보낸다.
      setFailed(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setEvents([]);
    setSummary(null);
    setFailed(false);
    void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  if (failed) {
    return (
      <section className="rounded-lg border border-neutral-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-neutral-800">크레딧</h2>
        <p className="mt-2 text-sm text-neutral-500">크레딧 이력을 불러오지 못했습니다.</p>
      </section>
    );
  }
  if (!summary) {
    return (
      <section className="rounded-lg border border-neutral-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-neutral-800">크레딧</h2>
        <p className="mt-2 text-sm text-neutral-500">불러오는 중…</p>
      </section>
    );
  }

  return (
    <section className="space-y-3 rounded-lg border border-neutral-200 bg-white p-4">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-sm font-semibold text-neutral-800">크레딧</h2>
        <span className="text-lg font-semibold tabular-nums text-neutral-900">{summary.balance}</span>
        <span className="text-xs text-neutral-500">현재 잔액 · 이력 {summary.total}건</span>
      </div>

      {summary.hasMerges && (
        <p className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
          계정 병합 이력이 있는 사용자입니다. 흡수된 계정의 결제 이력이 감사 보존을 위해 이 계정으로
          옮겨져 있어 <strong>변동 합계가 현재 잔액과 다를 수 있습니다</strong>. 잔액은 위 숫자가 정확합니다.
        </p>
      )}

      {events.length === 0 ? (
        <p className="text-sm text-neutral-500">크레딧 변동 이력이 없습니다.</p>
      ) : (
        <div className="overflow-x-auto rounded border border-neutral-200">
          <table className="min-w-full divide-y divide-neutral-200 text-sm">
            <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
              <tr>
                <th className="px-4 py-2">시각</th>
                <th className="px-4 py-2">유형</th>
                <th className="px-4 py-2 text-right">변동</th>
                <th className="px-4 py-2">상세</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {events.map((e, i) => (
                <tr key={`${e.at}:${e.type}:${i}`} className="hover:bg-neutral-50">
                  <td className="px-4 py-2 text-neutral-600">{formatIsoDateTime(e.at)}</td>
                  <td className="px-4 py-2">
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-medium ${
                        CREDIT_EVENT_BADGE[e.type] ?? "bg-neutral-100 text-neutral-700"
                      }`}
                    >
                      {CREDIT_EVENT_LABEL[e.type] ?? e.type}
                    </span>
                  </td>
                  <td
                    className={`px-4 py-2 text-right tabular-nums font-medium ${
                      e.delta < 0 ? "text-rose-600" : e.delta > 0 ? "text-emerald-700" : "text-neutral-500"
                    }`}
                  >
                    {e.delta > 0 ? `+${e.delta}` : e.delta}
                  </td>
                  <td className="px-4 py-2 text-neutral-600">
                    <CreditEventDetail event={e} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {events.length < summary.total && (
        <button
          onClick={() => void load(events.length)}
          disabled={loading}
          className="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-100 disabled:opacity-50"
        >
          {loading ? "불러오는 중…" : `더 보기 (${summary.total - events.length}건 남음)`}
        </button>
      )}
    </section>
  );
}

// 분리/환불은 "몇 분짜리 잡이었나" 가 핵심이라 길이 + 잡 ID, 나머지는 서버가 준 detail
// (구매 product id / 병합된 계정) 을 그대로 보여준다.
function CreditEventDetail({ event }: { event: AdminCreditEvent }) {
  if (event.type === "separation" || event.type === "refund") {
    return (
      <span className="flex flex-wrap items-baseline gap-2">
        <span>{event.sourceDurationMs ? formatDurationMs(event.sourceDurationMs) : "길이 미상"}</span>
        {event.jobId && <span className="font-mono text-xs text-neutral-400">{event.jobId}</span>}
      </span>
    );
  }
  return <span>{event.detail ?? "-"}</span>;
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
