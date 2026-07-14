import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { AdminAuthError } from "./api";

export interface AdminDataState<T> {
  data: T | null;
  error: string | null;
  /** in-flight 여부 — 재로드 시 stale 데이터를 유지한 채 스피너/버튼 disable 에 사용. */
  loading: boolean;
  /** 마지막 성공 로드 시각(epoch ms). null 이면 아직 첫 로드 전. */
  lastLoadedAt: number | null;
  /** 수동 새로고침. 자동 갱신 interval 과 동일 경로. */
  reload: () => void;
}

/**
 * admin 페이지 공용 데이터 로더. 마운트 시 1회 로드하고, refreshMs 지정 시 그 주기로
 * 자동 재로드한다. 재로드는 기존 data 를 지우지 않아(폴링 중 화면이 깜빡이지 않음) —
 * 페이지는 `!data` 일 때만 로딩 표시하면 된다. 401/403 은 로그인으로 redirect.
 *
 * loader 는 매 렌더 새로 만들어지는 인라인 클로저라 ref 로 최신본을 잡아 두고,
 * effect 는 refreshMs/navigate 에만 반응한다.
 */
export function useAdminData<T>(
  loader: () => Promise<T>,
  opts?: { refreshMs?: number },
): AdminDataState<T> {
  const navigate = useNavigate();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [lastLoadedAt, setLastLoadedAt] = useState<number | null>(null);

  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const mountedRef = useRef(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await loaderRef.current();
      if (!mountedRef.current) return;
      setData(res);
      setError(null);
      setLastLoadedAt(Date.now());
    } catch (e) {
      if (!mountedRef.current) return;
      if (e instanceof AdminAuthError) {
        navigate("/login", { replace: true });
        return;
      }
      setError(e instanceof Error ? e.message : "load failed");
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, [navigate]);

  const refreshMs = opts?.refreshMs;
  useEffect(() => {
    mountedRef.current = true;
    reload();
    if (!refreshMs) return () => { mountedRef.current = false; };
    const id = window.setInterval(reload, refreshMs);
    return () => {
      mountedRef.current = false;
      window.clearInterval(id);
    };
  }, [reload, refreshMs]);

  return { data, error, loading, lastLoadedAt, reload };
}
