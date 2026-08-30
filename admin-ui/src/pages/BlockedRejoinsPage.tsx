import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  adminPost,
  AdminAuthError,
  AdminBlockedRejoin,
  AdminBlockedRejoinsResponse,
  AdminUnblockRejoinResponse,
  adminFetch,
} from "../lib/api";
import { formatIsoDateTime } from "../lib/format";
import { providerBadgeClass, providerLabel } from "../lib/providers";
import { useAdminData } from "../lib/useAdminData";

/** 남은 차단 기간 — "3일 12시간" 형태. 만료가 지났으면 null (목록엔 안 나오지만 방어). */
function remainingLabel(blockedUntil: string): string | null {
  const ms = new Date(blockedUntil).getTime() - Date.now();
  if (Number.isNaN(ms) || ms <= 0) return null;
  const hours = Math.floor(ms / 3_600_000);
  const days = Math.floor(hours / 24);
  return days > 0 ? `${days}일 ${hours % 24}시간` : `${hours}시간`;
}

/**
 * 탈퇴 후 재가입 차단 목록 + 해제.
 *
 * 차단은 가입 보너스 크레딧을 노린 탈퇴→재가입 반복을 막지만, 실수로 탈퇴한 사용자와 앱 심사자가
 * 같이 갇힌다. 이 페이지가 그 유일한 해제 수단이다.
 *
 * 서버가 identity 를 해시로만 보관해(GDPR 최소수집) 이메일·이름을 표시할 수 없다 — 운영자는
 * provider + 탈퇴 시각으로 대상을 특정한다.
 */
export default function BlockedRejoinsPage() {
  const navigate = useNavigate();
  const { data, error, loading, reload } = useAdminData<AdminBlockedRejoinsResponse>(() =>
    adminFetch<AdminBlockedRejoinsResponse>("/api/v2/admin/blocked-rejoins"),
  );
  // 해제 진행 중인 identityHash — 중복 클릭 방지 + 버튼 라벨 전환.
  const [pending, setPending] = useState<string | null>(null);

  async function unblock(row: AdminBlockedRejoin) {
    const when = formatIsoDateTime(row.deletedAt);
    if (
      !window.confirm(
        `${providerLabel(row.provider)} · ${when} (UTC) 에 탈퇴한 계정의 재가입 차단을 해제할까요?\n` +
          `해제하면 즉시 다시 가입할 수 있고, 신규 가입이므로 가입 보너스 크레딧이 지급됩니다.`,
      )
    ) {
      return;
    }
    setPending(row.identityHash);
    try {
      const res = await adminPost<AdminUnblockRejoinResponse>(
        `/api/v2/admin/blocked-rejoins/${row.identityHash}/unblock`,
        {},
      );
      if (!res.unblocked) window.alert("이미 해제되었거나 차단 기간이 만료된 항목입니다.");
      reload();
    } catch (e) {
      if (e instanceof AdminAuthError) {
        navigate("/login", { replace: true });
        return;
      }
      window.alert(e instanceof Error ? e.message : "해제 실패");
    } finally {
      setPending(null);
    }
  }

  if (error) return <p className="text-sm text-rose-600">{error}</p>;
  if (!data) return <p className="text-sm text-neutral-500">불러오는 중…</p>;

  const rows = data.blocked;

  return (
    <div className="space-y-6">
      <header className="flex items-baseline justify-between">
        <div>
          <h1 className="text-xl font-semibold">재가입 차단</h1>
          <p className="mt-1 text-sm text-neutral-500">
            탈퇴 후 30일간 같은 계정의 재가입을 막습니다 · 가입 보너스 반복 수령 방지
          </p>
        </div>
        <button
          type="button"
          onClick={reload}
          disabled={loading}
          className="rounded border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
        >
          {loading ? "새로고침 중…" : "새로고침"}
        </button>
      </header>

      {rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-neutral-300 p-8 text-center text-sm text-neutral-500">
          현재 재가입이 차단된 계정이 없습니다.
        </p>
      ) : (
        <>
          <p className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            개인정보를 저장하지 않아 이메일·이름은 표시되지 않습니다. 로그인 수단과 탈퇴 시각으로
            대상을 확인하세요. (예: 앱 심사자가 방금 지운 계정 = 가장 위의 최근 항목)
          </p>
          <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
            <table className="min-w-full divide-y divide-neutral-200 text-sm">
              <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
                <tr>
                  <th className="px-4 py-3">로그인 수단</th>
                  <th className="px-4 py-3">탈퇴 시각 (UTC)</th>
                  <th className="px-4 py-3">차단 해제 예정 (UTC)</th>
                  <th className="px-4 py-3">남은 기간</th>
                  <th className="px-4 py-3 text-right">조치</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {rows.map((row) => (
                  <tr key={row.identityHash} className="hover:bg-neutral-50">
                    <td className="px-4 py-3">
                      <span
                        className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${providerBadgeClass(row.provider)}`}
                      >
                        {providerLabel(row.provider)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(row.deletedAt)}</td>
                    <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(row.blockedUntil)}</td>
                    <td className="px-4 py-3 tabular-nums text-neutral-600">
                      {remainingLabel(row.blockedUntil) ?? "만료됨"}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        type="button"
                        disabled={pending === row.identityHash}
                        onClick={() => unblock(row)}
                        className="rounded border border-blue-300 bg-blue-50 px-2 py-1 text-xs font-medium text-blue-800 hover:bg-blue-100 disabled:opacity-50"
                      >
                        {pending === row.identityHash ? "해제 중…" : "차단 해제"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
