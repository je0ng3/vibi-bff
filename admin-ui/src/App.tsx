import { Link, NavLink, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { loadAuth, clearAuth } from "./lib/auth";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import AnalyticsPage from "./pages/AnalyticsPage";
import JobsPage from "./pages/JobsPage";
import UsersPage from "./pages/UsersPage";
import UserDetailPage from "./pages/UserDetailPage";

// 상단 메뉴 — Overview(헬스) · Jobs(운영) · Analytics(추이) · Users(+액션). 순서 = 표시 순서.
const NAV: { to: string; label: string; end: boolean }[] = [
  { to: "/", label: "Overview", end: true },
  { to: "/jobs", label: "Jobs", end: false },
  { to: "/analytics", label: "Analytics", end: false },
  { to: "/users", label: "Users", end: false },
];

export default function App() {
  const location = useLocation();
  const isLogin = location.pathname === "/login";

  // 로그인 화면은 admin 헤더/컨테이너 밖에서 full-screen 으로 자체 레이아웃을 가짐.
  if (isLogin) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }

  const authed = !!loadAuth();

  return (
    <div className="min-h-screen bg-neutral-50 text-neutral-900">
      <header className="border-b border-neutral-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-6">
            <Link to="/" className="text-lg font-semibold tracking-tight">
              VIBI for Admin
            </Link>
            {authed && (
              <nav className="flex flex-wrap items-center gap-4 text-sm">
                {NAV.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) =>
                      isActive
                        ? "font-medium text-neutral-900"
                        : "text-neutral-600 hover:text-neutral-900"
                    }
                  >
                    {item.label}
                  </NavLink>
                ))}
              </nav>
            )}
          </div>
          {authed && (
            <button
              type="button"
              onClick={() => {
                clearAuth();
                window.location.hash = "#/login";
                window.location.reload();
              }}
              className="rounded border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-100"
            >
              Logout
            </button>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Routes>
          <Route path="/" element={<RequireAuth><DashboardPage /></RequireAuth>} />
          <Route path="/jobs" element={<RequireAuth><JobsPage /></RequireAuth>} />
          <Route path="/analytics" element={<RequireAuth><AnalyticsPage /></RequireAuth>} />
          <Route path="/users" element={<RequireAuth><UsersPage /></RequireAuth>} />
          <Route path="/users/:id" element={<RequireAuth><UserDetailPage /></RequireAuth>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const authed = !!loadAuth();
  if (!authed) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
