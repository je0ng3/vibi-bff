import { Link } from "react-router-dom";
import type { AdminActiveJob } from "../lib/api";
import { elapsedMs, formatDurationMs, formatElapsed, formatIsoDateTime } from "../lib/format";
import ClientBadge from "./ClientBadge";

/**
 * status='PROCESSING' 잡 목록. 가장 오래된 것 먼저 — stuck 의심 신호.
 * stuckMs 를 주면 그 나이를 넘은 행을 빨강으로 강조하고 Age 배지를 붙인다.
 * userEmail 은 Users 검색으로 링크 — "문제 잡 → 해당 사용자" 드릴다운.
 */
export default function ActiveJobsTable({
  rows,
  stuckMs,
}: {
  rows: AdminActiveJob[];
  stuckMs?: number;
}) {
  if (!rows.length) {
    return (
      <div className="flex h-24 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        진행 중인 잡이 없습니다.
      </div>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="min-w-full divide-y divide-neutral-200 text-sm">
        <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
          <tr>
            <th className="px-4 py-3">Type</th>
            <th className="px-4 py-3">Job ID</th>
            <th className="px-4 py-3">User</th>
            <th className="px-4 py-3 text-right">Duration</th>
            <th className="px-4 py-3">Started</th>
            <th className="px-4 py-3 text-right">Age</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">
          {rows.map((j) => {
            const age = elapsedMs(j.createdAt);
            const stuck = stuckMs != null && age > stuckMs;
            return (
              <tr key={j.jobId} className={stuck ? "bg-rose-50" : "hover:bg-neutral-50"}>
                <td className="px-4 py-3">
                  <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
                    {j.jobType}
                  </span>
                  {j.jobType === "separation" && <ClientBadge client={j.client} />}
                </td>
                <td className="px-4 py-3 font-mono text-xs">{j.jobId}</td>
                <td className="px-4 py-3">
                  <Link
                    to={`/users?q=${encodeURIComponent(j.userEmail)}`}
                    className="text-blue-600 hover:underline"
                  >
                    {j.userEmail}
                  </Link>
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{formatDurationMs(j.sourceDurationMs)}</td>
                <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(j.createdAt)}</td>
                <td
                  className={`px-4 py-3 text-right tabular-nums ${stuck ? "font-semibold text-rose-700" : "text-neutral-500"}`}
                >
                  {formatElapsed(age)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
