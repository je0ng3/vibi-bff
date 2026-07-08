/**
 * 제출 클라이언트('mobile'/'plugin') 배지 — JobStatusTable / ActiveJobsTable 공유.
 * plugin 은 보라 계열로 구분 (DailyChart 의 plugin 스택 색과 동일 계열).
 */
export default function ClientBadge({ client }: { client: string }) {
  const style =
    client === "plugin"
      ? "bg-violet-100 text-violet-700"
      : "bg-emerald-100 text-emerald-700";
  return (
    <span className={`ml-1.5 rounded px-2 py-0.5 text-xs font-medium ${style}`}>
      {client}
    </span>
  );
}
