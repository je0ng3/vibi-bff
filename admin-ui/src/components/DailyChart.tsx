import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AdminDailyStats } from "../lib/api";

export default function DailyChart({ data }: { data: AdminDailyStats[] }) {
  if (!data.length) {
    return (
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        선택한 기간에 사용 데이터가 없습니다.
      </div>
    );
  }
  const rows = data.map((d) => ({
    date: d.date.slice(5),
    renders: d.renderCount,
    mobileSeparations: d.mobileSeparationCount,
    pluginSeparations: d.pluginSeparationCount,
  }));
  return (
    <div className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
          <XAxis dataKey="date" stroke="#737373" fontSize={12} />
          <YAxis stroke="#737373" fontSize={12} allowDecimals={false} />
          <Tooltip
            contentStyle={{
              backgroundColor: "white",
              border: "1px solid #e5e5e5",
              borderRadius: 8,
              fontSize: 12,
            }}
          />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Bar dataKey="renders" name="Renders" fill="#3b82f6" />
          {/* separation 은 모바일+플러그인 스택 — 합이 기존 Separations 총량과 동일. */}
          <Bar dataKey="mobileSeparations" name="Separations (mobile)" stackId="sep" fill="#10b981" />
          <Bar dataKey="pluginSeparations" name="Separations (plugin)" stackId="sep" fill="#8b5cf6" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
