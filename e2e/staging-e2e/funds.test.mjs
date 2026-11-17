// L6 前半、L7（做法 07）：对账能跑；提现第一道审核的接口在（真实的提现单要等 L4 充值后由测试经理在浏览器里造，
// 交给 TopUP 之后的第二道审由 Amos 在 TopUP 做，这里不碰）。
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RUN, admin, expectError, ok } from './lib.mjs';

test('L7 对账：手动跑一次，状态能读', async () => {
  const r = ok(await admin('POST', '/api/admin/recon/run', {}), '跑对账');
  assert.ok(r && typeof r === 'object', '对账返回结果');
  ok(await admin('GET', '/api/admin/recon/status'), '对账状态');
});

test('L6 提现审核接口：不存在的提现单驳回返回业务错误（不是 403 / 500）', async () => {
  const r = await admin('POST', '/api/admin/withdrawals/1/reject', { reason: `${RUN} 不存在的单` });
  assert.ok(r.status !== 403 && r.status < 500, `测试管理员能调驳回、单不存在时是业务错误（实际 HTTP ${r.status}）`);
});

test('L6 待审核的提现：有的话只读核对字段，不审核（真实审核由测试经理按手工步骤做）', async (t) => {
  const list = ok(await admin('GET', '/api/admin/withdrawals?status=PENDING_REVIEW'), '待审核提现');
  const rows = Array.isArray(list?.items) ? list.items : Array.isArray(list) ? list : [];
  if (!rows.length) { t.skip('现在没有待审核的提现单（未验证：要先有充值和提现）'); return; }
  for (const w of rows) {
    assert.ok(!String(w.email || '').includes('@'), '邮箱已打码');
  }
});

test('钱包提供方：检查连接（只读结果）', async () => {
  const r = await admin('POST', '/api/admin/payment/check', {});
  assert.ok(r.status === 200, `检查连接返回 200（实际 HTTP ${r.status}）`);
  expectError(await admin('PUT', '/api/admin/payment/networks/TRC20', { open: false, reason: RUN }), 403, 'TEST_ADMIN_FORBIDDEN', '关充值网络');
});
