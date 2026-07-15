import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { adminFetch, adminPost, AdminAuthError, AdminUsersResponse } from "../lib/api";
import { loadAuth, decodeSub } from "../lib/auth";
import { formatDurationMs, formatIsoDateTime } from "../lib/format";
import { providerBadgeClass, providerLabel } from "../lib/providers";

const PAGE_SIZE = 50;

export default function UsersPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const offset = Math.max(0, Number.parseInt(params.get("offset") ?? "0", 10) || 0);
  const queryFromUrl = params.get("q") ?? "";
  // 클라이언트 필터 — 잡 이력 기준 (mobile: render 또는 mobile 분리, plugin: plugin 분리).
  const clientFilter = params.get("client") ?? "";

  // 로그인한 운영자 본인의 userId — 자기 role 변경 버튼을 숨긴다 (서버도 400 으로 차단).
  const auth = loadAuth();
  const selfId = auth ? decodeSub(auth.token) : null;

  // 검색 input — 사용자가 타이핑 중인 raw 값. URL/요청에는 debounce 적용.
  const [queryDraft, setQueryDraft] = useState(queryFromUrl);
  const [data, setData] = useState<AdminUsersResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  // role 변경 진행 중인 userId (버튼 중복 클릭 방지 + 스피너 표시).
  const [pendingRole, setPendingRole] = useState<string | null>(null);

  async function changeRole(userId: string, nextRole: "admin" | "user") {
    const label = nextRole === "admin" ? "관리자로 승격" : "일반 사용자로 강등";
    if (!window.confirm(`${label}하시겠습니까? (대상 사용자는 재로그인 후 반영됩니다)`)) return;
    setPendingRole(userId);
    try {
      await adminPost(`/api/v2/admin/users/${userId}/role`, { role: nextRole });
      setData((prev) =>
        prev
          ? { ...prev, users: prev.users.map((u) => (u.userId === userId ? { ...u, role: nextRole } : u)) }
          : prev,
      );
    } catch (e) {
      if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
      window.alert(e instanceof Error ? e.message : "role 변경 실패");
    } finally {
      setPendingRole(null);
    }
  }

  // 250ms debounce — 빠른 타이핑 시 매 키스트로크에 fetch 안 함.
  useEffect(() => {
    if (queryDraft === queryFromUrl) return;
    const id = window.setTimeout(() => {
      const next = new URLSearchParams(params);
      if (queryDraft.trim()) next.set("q", queryDraft.trim());
      else next.delete("q");
      next.delete("offset");
      setParams(next, { replace: true });
    }, 250);
    return () => window.clearTimeout(id);
  }, [queryDraft, queryFromUrl, params, setParams]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const qs = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
        if (queryFromUrl) qs.set("q", queryFromUrl);
        if (clientFilter) qs.set("client", clientFilter);
        const res = await adminFetch<AdminUsersResponse>(`/api/v2/admin/users?${qs.toString()}`);
        if (!cancelled) setData(res);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setError(e instanceof Error ? e.message : "load failed");
      }
    })();
    return () => { cancelled = true; };
  }, [offset, queryFromUrl, clientFilter, navigate]);

  if (error) return <p className="text-sm text-rose-600">{error}</p>;
  if (!data) return <p className="text-sm text-neutral-500">불러오는 중…</p>;

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const setOffset = (next: number) => {
    const np = new URLSearchParams(params);
    np.set("offset", String(Math.max(0, next)));
    setParams(np);
  };

  return (
    <div className="space-y-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold">Users</h1>
        <span className="text-sm text-neutral-500">총 {data.total.toLocaleString()}명</span>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <input
          type="search"
          value={queryDraft}
          onChange={(e) => setQueryDraft(e.target.value)}
          placeholder="이메일 또는 이름으로 검색…"
          className="w-full max-w-md rounded border border-neutral-300 bg-white px-3 py-2 text-sm placeholder:text-neutral-400 focus:border-blue-500 focus:outline-none"
        />
        <select
          value={clientFilter}
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.target.value) next.set("client", e.target.value);
            else next.delete("client");
            next.delete("offset");
            setParams(next, { replace: true });
          }}
          className="rounded border border-neutral-300 bg-white px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
        >
          <option value="">전체 클라이언트</option>
          <option value="mobile">모바일 앱</option>
          <option value="plugin">Adobe 플러그인</option>
        </select>
      </div>

      {data.users.length === 0 ? (
        <p className="rounded-lg border border-dashed border-neutral-300 p-8 text-center text-sm text-neutral-500">
          {queryFromUrl ? "검색 결과가 없습니다." : "아직 가입한 사용자가 없습니다."}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
          <table className="min-w-full divide-y divide-neutral-200 text-sm">
            <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
              <tr>
                <th className="px-4 py-3">User</th>
                <th className="px-4 py-3">Role</th>
                <th className="px-4 py-3 text-right">Renders</th>
                <th className="px-4 py-3 text-right">Sep (mobile)</th>
                <th className="px-4 py-3 text-right">Sep (plugin)</th>
                <th className="px-4 py-3 text-right">Uploaded</th>
                <th className="px-4 py-3">Last activity</th>
                <th className="px-4 py-3 text-right">Role 변경</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {data.users.map((u) => (
                <tr key={u.userId} className="hover:bg-neutral-50">
                  <td className="px-4 py-3">
                    <Link to={`/users/${u.userId}`} className="text-blue-600 hover:underline">
                      {u.name || u.email}
                    </Link>
                    <div className="text-xs text-neutral-500">{u.email}</div>
                    {u.linkedProviders.length > 0 && (
                      <div className="mt-1 flex flex-wrap gap-1">
                        {u.linkedProviders.map((p) => (
                          <span
                            key={p}
                            className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${providerBadgeClass(p)}`}
                          >
                            {providerLabel(p)}
                          </span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={
                        u.role === "admin"
                          ? "rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                          : "text-xs text-neutral-500"
                      }
                    >
                      {u.role}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{u.totalRenders.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{u.mobileSeparations.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{u.pluginSeparations.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{formatDurationMs(u.totalSourceDurationMs)}</td>
                  <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(u.lastActivityAt)}</td>
                  <td className="px-4 py-3 text-right">
                    {u.userId === selfId ? (
                      <span className="text-xs text-neutral-400">본인</span>
                    ) : u.role === "admin" ? (
                      <button
                        type="button"
                        disabled={pendingRole === u.userId}
                        onClick={() => changeRole(u.userId, "user")}
                        className="rounded border border-neutral-300 px-2 py-1 text-xs text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
                      >
                        {pendingRole === u.userId ? "변경 중…" : "관리자 해제"}
                      </button>
                    ) : (
                      <button
                        type="button"
                        disabled={pendingRole === u.userId}
                        onClick={() => changeRole(u.userId, "admin")}
                        className="rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs font-medium text-amber-800 hover:bg-amber-100 disabled:opacity-50"
                      >
                        {pendingRole === u.userId ? "변경 중…" : "관리자로 승격"}
                      </button>
                    )}
                  </td>
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
              <button
                onClick={() => setOffset(offset - PAGE_SIZE)}
                className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100"
              >
                Prev
              </button>
            )}
            {currentPage < totalPages && (
              <button
                onClick={() => setOffset(offset + PAGE_SIZE)}
                className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100"
              >
                Next
              </button>
            )}
          </div>
        </nav>
      )}
    </div>
  );
}
