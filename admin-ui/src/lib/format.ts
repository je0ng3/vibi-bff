export function formatDurationMs(ms: number): string {
  if (!ms || ms <= 0) return "-";
  const totalSec = Math.round(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

/** 체류기간(일) → 표시 문자열. 0=데이터 없음(-), 1일 미만은 시간, 그 외 "N.N일". */
export function formatTenureDays(days: number): string {
  if (!days || days <= 0) return "-";
  if (days < 1) return `${Math.round(days * 24)}시간`;
  return `${days.toFixed(1)}일`;
}

/** epoch ms → 로컬 HH:MM:SS. "마지막 갱신 시각" 표시용 (운영자 현지 시간). */
export function formatClock(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** ISO 타임스탬프 → 경과 시간 ms (now - iso). 파싱 실패/미래값은 0. */
export function elapsedMs(iso: string): number {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : Math.max(0, Date.now() - t);
}

/** 경과 ms → "12m" / "1h 5m" 압축 표기. stuck 잡 나이 배지용. */
export function formatElapsed(ms: number): string {
  const totalMin = Math.floor(ms / 60000);
  if (totalMin < 1) return "<1m";
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export function formatIsoDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => n.toString().padStart(2, "0");
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
  );
}
