// L1 + 测试管理员边界（做法 07）：健康检查、测试管理员只读页面、白名单以外 403、令牌不对 401、改东西只收 POST、客户端域名没有后台接口。
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { APP, admin, adminWrongToken, expectError, ok, raw } from './lib.mjs';

test('L1 健康检查：客户端域名 env=test、不带业务告警；后台域名带告警', async () => {
  const h = ok(await raw(APP, '/api/health'), '客户端域名健康检查');
  assert.ok(h.status === 'UP' && h.env === 'test', '客户端域名：status=UP、env=test');
  assert.ok(h.alerts === undefined, '客户端域名不返回 alerts');
  assert.ok(typeof h.version === 'string' && /^[0-9a-f]{40}$/.test(h.version), 'version 是完整提交号');
});

test('测试管理员能读后台的页面（不含客户资料）', async () => {
  for (const p of ['/api/admin/dashboard', '/api/admin/withdrawals', '/api/admin/withdrawals/summary', '/api/admin/deposits', '/api/admin/recon/status',
    '/api/admin/payment', '/api/admin/risk', '/api/admin/symbols', '/api/admin/quote-service/overview', '/api/admin/quote-service/sources']) {
    ok(await admin('GET', p), p);
  }
});

test('白名单以外一律 403：管理员、客户、KYC、系统配置、审计、提供方切换、总开关', async () => {
  for (const p of ['/api/admin/admins', '/api/admin/customers', '/api/admin/kyc', '/api/admin/configs', '/api/admin/audit-logs', '/api/admin/me']) {
    expectError(await admin('GET', p), 403, 'TEST_ADMIN_FORBIDDEN', p);
  }
  expectError(await admin('POST', '/api/admin/payment/switch', { provider: 'TOPUP', reason: 'e2e' }), 403, 'TEST_ADMIN_FORBIDDEN', '切换钱包提供方');
  expectError(await admin('PUT', '/api/admin/quote-service/gate', { open: true, amosApproved: true, reason: 'e2e' }), 403, 'TEST_ADMIN_FORBIDDEN', '对外开放总开关');
  expectError(await admin('POST', '/api/admin/risk/global-pause', { enabled: true, reason: 'e2e' }), 403, 'TEST_ADMIN_FORBIDDEN', '全局禁开');
});

test('令牌不对 401；改东西的接口用 GET 调 405', async () => {
  expectError(await adminWrongToken('GET', '/api/admin/dashboard'), 401, 'UNAUTHORIZED', '错的令牌');
  const r = await admin('GET', '/api/admin/recon/run');
  assert.ok(r.status === 405, `GET 调对账返回 405（实际 HTTP ${r.status}）`);
});

test('客户端域名上没有后台接口、端到端测试接口、管理端点', async () => {
  for (const p of ['/api/admin/dashboard', '/api/e2e/code', '/actuator/health']) {
    const r = await raw(APP, p);
    assert.ok(r.status === 404, `${p} 返回 404（实际 HTTP ${r.status}）`);
  }
});
