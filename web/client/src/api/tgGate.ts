/** 方案 B 引导页的匿名计数（POST /api/public/tg-gate-hit，后端 common.web.TgGateController）：只发平台大类，不带 Cookie、不发 UA */
import type { PlatformFamily } from '../lib/telegramInApp';
import { IS_MOCK } from './mode';

export const GUIDE_HIT_PATH = '/api/public/tg-gate-hit';

/** 计数请求：请求体只有平台大类；不带 Cookie（credentials: omit） */
export function guideHitRequest(platform: PlatformFamily): RequestInit {
  return { method: 'POST', credentials: 'omit', keepalive: true, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ platform }) };
}

/** 引导页显示一次计一次；失败不管。原型模式不发 */
export function reportGuideShown(platform: PlatformFamily): void {
  if (IS_MOCK) return;
  try {
    void fetch(GUIDE_HIT_PATH, guideHitRequest(platform)).catch(() => undefined);
  } catch {
    // 计数失败不影响页面
  }
}
