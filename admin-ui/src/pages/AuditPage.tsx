import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { adminFetch, AdminAuditEntry, AdminAuditResponse, AdminAuthError } from "../lib/api";
import { formatIsoDateTime } from "../lib/format";
import DataTable from "../components/DataTable";

const PAGE_SIZE = 50;

// action → 라벨/배지. BFF AdminAuditAction 과 1:1.
const ACTION_LABEL: Record<string, string> = {
  credit_grant: "크레딧 지급",
  set_role: "권한 변경",
  unblock_rejoin: "재가입 차단 해제",
};

const ACTION_BADGE: Record<string, string> = {
  credit_grant: "bg-violet-100 text-violet-800",
  set_role: "bg-amber-100 text-amber-800",
  unblock_rejoin: "bg-blue-100 text-blue-800",
};

/**
 * 운영자 액션 감사 로그 — 누가·언제·누구에게 무엇을 했나. append-only 라 수정/삭제 UI 는 없다.
 *
 * 크레딧 수동 지급이 생기면서 필요해진 화면이다: 잔액이 이상할 때 "누가 얼마를 왜 넣었나" 를
 * 여기서 확인한다. role 변경·재가입 차단 해제도 같은 스트림에 남는다.
 */
export default function AuditPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const offset = Math.max(0, Number.parseInt(params.get("offset") ?? "0", 10) || 0);

  const [data, setData] = useState<AdminAuditResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await adminFetch<AdminAuditResponse>(
          `/api/v2/admin/audit?limit=${PAGE_SIZE}&offset=${offset}`,
        );
        if (!cancelled) setData(res);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setError(e instanceof Error ? e.message : "load failed");
      }
    })();
    return () => { cancelled = true; };
  }, [offset, navigate]);

  if (error) return <p className="text-sm text-rose-600">{error}</p>;
  if (!data) return <p className="text-sm text-neutral-500">불러오는 중…</p>;

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const setOffset = (next: number) => setParams({ offset: String(Math.max(0, next)) });

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-xl font-semibold">감사 로그</h1>
        <p className="mt-1 text-sm text-neutral-500">
          운영자가 수행한 크레딧 지급 · 권한 변경 · 재가입 차단 해제 기록. 최신순 · 총 {data.total}건
        </p>
      </header>

      {data.entries.length === 0 ? (
        <p className="rounded-lg border border-dashed border-neutral-300 p-8 text-center text-sm text-neutral-500">
          아직 기록된 운영자 액션이 없습니다.
        </p>
      ) : (
        <DataTable
          columns={[
            { label: "시각 (UTC)" },
            { label: "실행자" },
            { label: "액션" },
            { label: "대상" },
            { label: "수량", align: "right" },
            { label: "상세" },
          ]}
        >
          {data.entries.map((e) => (
            <tr key={e.id} className="hover:bg-neutral-50">
              <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(e.at)}</td>
              <td className="px-4 py-3 text-neutral-700">{e.actorEmail}</td>
              <td className="px-4 py-3">
                <span
                  className={`rounded px-2 py-0.5 text-xs font-medium ${
                    ACTION_BADGE[e.action] ?? "bg-neutral-100 text-neutral-700"
                  }`}
                >
                  {ACTION_LABEL[e.action] ?? e.action}
                </span>
              </td>
              <td className="px-4 py-3"><TargetCell entry={e} /></td>
              <td className="px-4 py-3 text-right tabular-nums text-neutral-700">
                {e.amount != null ? `+${e.amount}` : "-"}
              </td>
              <td className="px-4 py-3 text-neutral-600">{e.detail ?? "-"}</td>
            </tr>
          ))}
        </DataTable>
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

// 대상 사용자는 상세 페이지로 링크. 탈퇴했거나(users row 없음) 대상이 사용자가 아닌
// 액션(재가입 차단 해제)은 링크할 곳이 없어 텍스트로만 표시한다.
function TargetCell({ entry }: { entry: AdminAuditEntry }) {
  if (!entry.targetUserId) return <span className="text-neutral-400">-</span>;
  return (
    <Link to={`/users/${entry.targetUserId}`} className="text-blue-600 hover:underline">
      {entry.targetEmail ?? entry.targetUserId}
    </Link>
  );
}
