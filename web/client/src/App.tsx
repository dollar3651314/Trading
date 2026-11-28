/**
 * 路由（站点地图见需求说明书 5.1）。客户端部署在根路径 `/`，使用浏览器路由。
 */
import { lazy, Suspense, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AccountProvider } from './app/AccountProvider';
import { AuthProvider } from './app/AuthProvider';
import { useAuth } from './app/contexts';
import { SymbolsProvider } from './app/SymbolsProvider';
import { ThemeProvider } from './app/ThemeProvider';
import { ToastProvider } from './app/ToastProvider';
import { Loading } from './components/AsyncView';
import { Layout } from './components/Layout';
import { startEngine } from './api/trading';
import { useKeyboardInset } from './hooks/useKeyboardInset';
import { TelegramOnly } from './pages/tg/TelegramOnly';

const Login = lazy(() => import('./pages/auth/Login'));
const Register = lazy(() => import('./pages/auth/Register'));
const ForgotPassword = lazy(() => import('./pages/auth/ForgotPassword'));
const Markets = lazy(() => import('./pages/Markets'));
const Trade = lazy(() => import('./pages/Trade'));
const Positions = lazy(() => import('./pages/Positions'));
const Assets = lazy(() => import('./pages/assets/Assets'));
const Ledger = lazy(() => import('./pages/assets/Ledger'));
const Withdraw = lazy(() => import('./pages/assets/Withdraw'));
const Whitelist = lazy(() => import('./pages/assets/Whitelist'));
const Records = lazy(() => import('./pages/assets/Records'));
const Me = lazy(() => import('./pages/me/Me'));
const Profile = lazy(() => import('./pages/me/Profile'));
const Kyc = lazy(() => import('./pages/me/Kyc'));
const Notifications = lazy(() => import('./pages/me/Notifications'));
const NotifyPrefs = lazy(() => import('./pages/me/NotifyPrefs'));
const ChangePassword = lazy(() => import('./pages/me/ChangePassword'));
const WithdrawPin = lazy(() => import('./pages/me/WithdrawPin'));
const Recovery = lazy(() => import('./pages/me/Recovery'));
const WithdrawFreeze = lazy(() => import('./pages/me/WithdrawFreeze'));
const NotFound = lazy(() => import('./pages/NotFound'));
const TgHint = lazy(() => import('./pages/tg/TgHint'));
const TgEntry = lazy(() => import('./pages/tg/TgEntry'));
const TgLink = lazy(() => import('./pages/tg/TgLink'));

// 原型：行情驱动的挂单成交、止盈止损、强平在浏览器内模拟（真实环境在服务端，这里什么都不做）
startEngine();

export function RequireAuth({ children }: { children: ReactNode }) {
  const { session, checking, securityLogout } = useAuth();
  const location = useLocation();
  if (checking) return <Loading rows={4} />;
  // 验收反馈 1b：会话是因为安全事件没的，去"回机器人点「重新授权」"的提示页，不去登录页
  if (!session && securityLogout) return <Navigate to="/tg?r=security" replace />;
  // 11/28：从机器人的普通按钮进来（?from=tg）、这个浏览器里没有有效会话：短页「回到机器人继续」（深链接 /start open）
  if (!session && new URLSearchParams(location.search).get('from') === 'tg') return <Navigate to="/tg?r=continue" replace />;
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { session, checking } = useAuth();
  if (checking) return <Loading rows={4} />;
  return session ? <Navigate to="/" replace /> : <>{children}</>;
}

export function AppRoutes() {
  // 键盘弹出时主按钮不被遮住（UX-B2）
  useKeyboardInset();
  return (
    <Suspense fallback={<Loading rows={4} />}>
      <Routes>
        {/* 第一阶段只走 Telegram（TB-33）：后端说 telegramOnly 时这三页换成"请在 Telegram 打开"提示页 */}
        <Route path="/login" element={<GuestOnly><TelegramOnly><Login /></TelegramOnly></GuestOnly>} />
        <Route path="/register" element={<GuestOnly><TelegramOnly><Register /></TelegramOnly></GuestOnly>} />
        <Route path="/forgot-password" element={<TelegramOnly><ForgotPassword /></TelegramOnly>} />
        {/* TG 机器人第 2 批：提示页（从机器人进来没成功时 ?r=原因）、提示页上的两个入口、一次性链接（退路） */}
        <Route path="/tg" element={<TgHint />} />
        <Route path="/tg/freeze" element={<TgEntry kind="freeze" />} />
        <Route path="/tg/recover" element={<TgEntry kind="recover" />} />
        <Route path="/tg-link" element={<TgLink />} />
        <Route
          element={
            <RequireAuth>
              <SymbolsProvider>
                <AccountProvider>
                  <Layout />
                </AccountProvider>
              </SymbolsProvider>
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="/markets" replace />} />
          <Route path="markets" element={<Markets />} />
          <Route path="trade" element={<Trade />} />
          <Route path="trade/:symbol" element={<Trade />} />
          <Route path="positions" element={<Positions />} />
          <Route path="assets" element={<Assets />} />
          <Route path="assets/ledger" element={<Ledger />} />
          <Route path="assets/withdraw" element={<Withdraw />} />
          <Route path="assets/whitelist" element={<Whitelist />} />
          <Route path="assets/records" element={<Records />} />
          <Route path="me" element={<Me />} />
          <Route path="me/profile" element={<Profile />} />
          <Route path="me/kyc" element={<Kyc />} />
          <Route path="me/notifications" element={<Notifications />} />
          <Route path="me/notify-prefs" element={<NotifyPrefs />} />
          <Route path="me/password" element={<ChangePassword />} />
          <Route path="me/withdraw-pin" element={<WithdrawPin />} />
          <Route path="me/recovery" element={<Recovery />} />
          <Route path="me/withdraw-freeze" element={<WithdrawFreeze />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <ToastProvider>
          <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <AppRoutes />
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
