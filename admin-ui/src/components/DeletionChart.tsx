import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AdminDeletionDaily } from "../lib/api";

/**
 * 일별 회원탈퇴 — Google/Apple 스택 area. 가입(SignupChart) 대비 이탈 비교용.
 * 색은 이탈 신호로 rose 계열. V15 이전 하드 삭제분은 기록이 없어 그래프에 안 나온다.
 */
export default function DeletionChart({ data }: { data: AdminDeletionDaily[] }) {
  const total = data.reduce((acc, d) => acc + d.googleCount + d.appleCount, 0);
  if (total === 0) {
    return (
      <div className="flex h-48 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        선택 기간에 탈퇴가 없습니다.
      </div>
    );
  }
  const rows = data.map((d) => ({ date: d.date.slice(5), Google: d.googleCount, Apple: d.appleCount }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={rows} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
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
          <Area type="monotone" dataKey="Apple" stackId="1" stroke="#9f1239" fill="#9f1239" fillOpacity={0.55} />
          <Area type="monotone" dataKey="Google" stackId="1" stroke="#f43f5e" fill="#f43f5e" fillOpacity={0.55} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
