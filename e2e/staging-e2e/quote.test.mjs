// L8 报价对外（做法 07）：外面访问 /internal/** 一律 404；不签名 401；测试管理员建一个内部测试 Key、签名查报价 200，跑完停用（自己收尾）。
// 需要 STAGING_QUOTE_URL（报价对外子域名）；没有时跳过，算"未验证"。
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { QUOTE, RUN, admin, expectError, ok, quoteSigned, raw } from './lib.mjs';

const skip = !QUOTE && '没有 STAGING_QUOTE_URL（未验证）';
const NOTE = 'staging-e2e 自动验收用（可停用 Key，不要删客户）';

test('L8 /internal/** 从外面一律 404；不签名 401', { skip }, async () => {
  for (const p of ['/internal/open/overview', '/internal/ws/quotes', '/INTERNAL/catalog', '//internal/x']) {
    const r = await raw(QUOTE, p);
    assert.ok(r.status === 404, `${p} 返回 404（实际 HTTP ${r.status}）`);
  }
  const r = await raw(QUOTE, '/v1/quotes?symbols=EURUSD');
  assert.ok(r.status === 401, `不签名返回 401（实际 HTTP ${r.status}）`);
});

test('L8 内部测试 Key：建 → 签名查报价 → 停用；不能建外部 Key', { skip }, async () => {
  const ov = ok(await admin('GET', '/api/admin/quote-service/overview'), '报价服务概览');
  let client = (ov.clients || []).find((c) => c.note === NOTE && c.active);
  if (!client) client = ok(await admin('POST', '/api/admin/quote-service/clients', { name: 'staging-e2e', email: 'staging-e2e@example.com', note: NOTE, reason: RUN }), '建测试客户');
  assert.ok(Number.isFinite(Number(client.id)), '测试客户有编号');
  expectError(await admin('POST', '/api/admin/quote-service/keys', { clientId: client.id, kind: 'EXTERNAL', symbols: [], reason: RUN }), 403, 'TEST_ADMIN_FORBIDDEN', '建外部 Key');
  const created = ok(await admin('POST', '/api/admin/quote-service/keys', { clientId: client.id, kind: 'INTERNAL_TEST', symbols: ['EURUSD'], reason: RUN }), '建内部测试 Key');
  const keyId = created?.key?.keyId;
  const secret = created?.secret;
  assert.ok(typeof keyId === 'string' && typeof secret === 'string', '返回了 Key ID 和只显示一次的 Secret');
  try {
    const q = await quoteSigned(keyId, secret, '/v1/quotes?symbols=EURUSD');
    assert.ok(q.status === 200, `签名查报价返回 200（实际 HTTP ${q.status}${q.body?.code ? ` ${q.body.code}` : ''}）`);
  } finally {
    ok(await admin('POST', `/api/admin/quote-service/keys/${encodeURIComponent(keyId)}/disable`, { reason: `${RUN} 收尾` }), '停用这次建的 Key');
  }
});
