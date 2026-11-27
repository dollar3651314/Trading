/**
 * 第一阶段只走 Telegram（TB-33）：后端说 telegramOnly 时，注册、登录、忘记密码页面都换成"请在 Telegram 打开"提示页。
 * 读不到登录方式（后端不通）时照常显示原页面（后端那边这些接口照样关着，提交会得到"现在只能从 Telegram 机器人进入"）。
 */
import { useEffect, useState, type ReactNode } from 'react';
import { getAuthOptions } from '../../api/auth';
import { Loading } from '../../components/AsyncView';
import TgHint from './TgHint';

export function TelegramOnly({ children }: { children: ReactNode }) {
  const [only, setOnly] = useState<boolean | null>(null);
  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => alive && setOnly(o.telegramOnly),
      () => alive && setOnly(false),
    );
    return () => {
      alive = false;
    };
  }, []);
  if (only === null) return <Loading rows={4} />;
  return only ? <TgHint /> : <>{children}</>;
}
