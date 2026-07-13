import { formatClock } from "../lib/format";

/** admin 페이지 공용 로딩/에러 표시. 옛 DashboardPage 인라인 뷰를 공유용으로 추출. */

export function Loading() {
  return <p className="text-sm text-neutral-500">불러오는 중…</p>;
}

export function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">
      {message}
    </div>
  );
}

/** 섹션 제목 + 부연 설명. 페이지 내부 여러 섹션이 반복하는 패턴. */
export function SectionHeading({ title, hint }: { title: string; hint?: string }) {
  return (
    <div>
      <h1 className="text-xl font-semibold">{title}</h1>
      {hint && <p className="mt-1 text-sm text-neutral-500">{hint}</p>}
    </div>
  );
}

/**
 * 페이지 상단 헤더 — 제목/설명 + (옵션) 마지막 갱신 시각 & 수동 새로고침 버튼.
 * onReload 를 주면 우측에 새로고침 컨트롤을 붙인다 (Overview·Analytics 공용).
 */
export function PageHeader({
  title,
  hint,
  lastLoadedAt,
  loading,
  onReload,
}: {
  title: string;
  hint?: string;
  lastLoadedAt?: number | null;
  loading?: boolean;
  onReload?: () => void;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {hint && <p className="mt-1 text-sm text-neutral-500">{hint}</p>}
      </div>
      {onReload && (
        <div className="flex items-center gap-3 text-sm text-neutral-500">
          {lastLoadedAt != null && <span>갱신 {formatClock(lastLoadedAt)}</span>}
          <button
            type="button"
            onClick={onReload}
            disabled={loading}
            className="rounded border border-neutral-300 px-3 py-1.5 text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
          >
            {loading ? "새로고침 중…" : "새로고침"}
          </button>
        </div>
      )}
    </div>
  );
}

type Tone = "ok" | "warn" | "alert";

const TONE_STYLES: Record<Tone, string> = {
  ok: "border-neutral-200 bg-white",
  warn: "border-amber-300 bg-amber-50",
  alert: "border-rose-300 bg-rose-50",
};

const TONE_LABEL: Record<Tone, string> = {
  ok: "text-neutral-500",
  warn: "text-amber-700",
  alert: "text-rose-700",
};

const TONE_VALUE: Record<Tone, string> = {
  ok: "text-neutral-900",
  warn: "text-amber-900",
  alert: "text-rose-900",
};

/**
 * Overview 헬스 카드 — 임계값 상태에 따라 색을 바꿔 "지금 문제 있나?"를 한눈에.
 * tone=alert 는 즉시 조치 신호(빨강), warn 은 주의(amber), ok 는 정상(중립).
 */
export function HealthCard({
  label,
  value,
  sub,
  tone = "ok",
}: {
  label: string;
  value: string | number;
  sub?: string;
  tone?: Tone;
}) {
  return (
    <div className={`rounded-lg border p-5 ${TONE_STYLES[tone]}`}>
      <div className={`text-xs font-medium uppercase tracking-wide ${TONE_LABEL[tone]}`}>
        {label}
      </div>
      <div className={`mt-2 text-3xl font-semibold tabular-nums tracking-tight ${TONE_VALUE[tone]}`}>
        {value}
      </div>
      {sub && <div className={`mt-1 text-xs ${TONE_LABEL[tone]}`}>{sub}</div>}
    </div>
  );
}
