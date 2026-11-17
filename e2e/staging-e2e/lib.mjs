// 测试环境验收用例（e2e/staging-e2e/）共用的小工具：只用 Node 自带的 fetch，不需要 npm 安装。
//   - 地址、令牌只从环境变量来（GitHub 环境 staging-e2e 的密钥），不写在用例里
//   - 令牌在这个文件加载时读进来，随即从 process.env 删掉，用例拿不到变量本身，只能用下面的请求函数
//     （这挡不住故意作恶的用例代码：同一个进程里总有办法读到；真正的防线是 GitHub 环境的 Required reviewers 和定期换令牌）
//   - 这里的函数不打印、不返回请求头；出错信息只有路径、HTTP 状态和接口返回的错误码（code），不含响应内容、域名和令牌：
//     仓库是公开的，运行日志谁都能看。用例里只许用 assert.ok(条件, '说明') 和这里的 ok()、expectError()；
//     assert.equal、deepEqual、strictEqual、match 失败时 node 会把实际值（接口返回）打印出来，不要用；也不要 console.log 接口返回
//   - 测试管理员调后台接口：admin('GET', '/api/admin/dashboard')、admin('POST', '/api/admin/recon/run', {})
import assert from 'node:assert/strict';
import { createHmac, randomUUID } from 'node:crypto';

const strip = (v) => String(v || '').trim().replace(/\/+$/, '');
export const APP = strip(process.env.STAGING_URL);
export const ADMIN = strip(process.env.STAGING_ADMIN_URL);
export const QUOTE = strip(process.env.STAGING_QUOTE_URL);
const TOKEN = String(process.env.STAGING_TEST_ADMIN_TOKEN || '').trim();
delete process.env.STAGING_TEST_ADMIN_TOKEN;
const HEADER = 'x-trading-test-admin';

if (!APP || !ADMIN || !TOKEN) throw new Error('需要环境变量 STAGING_URL、STAGING_ADMIN_URL、STAGING_TEST_ADMIN_TOKEN（GitHub 环境 staging-e2e 的密钥）');

/** 这次运行的编号：造的数据（原因、备注）都带上它，审计和数据里能认出是哪一次运行造的 */
export const RUN = `e2e-${String(process.env.GITHUB_RUN_ID || 'local').replace(/[^0-9a-z]/gi, '')}-${String(process.env.GITHUB_RUN_ATTEMPT || '1').replace(/[^0-9]/g, '')}-${randomUUID().slice(0, 8)}`;

async function req(base, path, { method = 'GET', body, headers = {}, token = '', timeoutMs = 30_000 } = {}) {
  const h = { ...headers };
  if (token) h[HEADER] = token;
  if (body !== undefined) h['content-type'] = 'application/json';
  let res;
  try {
    res = await fetch(base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs), redirect: 'manual' });
  } catch (e) {
    throw new Error(`${method} ${path}：连不上（${e?.name || 'error'}）`);
  }
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = null; }
  return { status: res.status, body: json };
}

/** 测试管理员调后台接口（后台域名）。返回 { status, body }，不抛错（要 200 用 ok()） */
export const admin = (method, path, body, opts = {}) => req(ADMIN, path, { method, body, token: TOKEN, ...opts });
/** 带一个错的令牌调后台接口（核对 401） */
export const adminWrongToken = (method, path) => req(ADMIN, path, { method, token: `wrong-${randomUUID()}` });
/** 不带令牌请求任意域名：raw(APP, '/api/health') */
export const raw = (base, path, opts = {}) => req(base, path, opts);

const why = (r) => `HTTP ${r.status}${r.body?.code ? ` ${r.body.code}` : ''}`;

/** 断言 200 并返回 body；不是 200 时报出说明、状态和错误码 */
export function ok(r, what = '') {
  if (r.status !== 200) assert.fail(`${what} 返回 ${why(r)}`);
  return r.body;
}

/** 断言是某个错误：expectError(r, 403, 'TEST_ADMIN_FORBIDDEN', '说明') */
export function expectError(r, status, code, what = '') {
  if (r.status !== status || (code && r.body?.code !== code)) assert.fail(`${what} 期望 HTTP ${status}${code ? ` ${code}` : ''}，实际 ${why(r)}`);
}

/** 报价对外的签名请求（和开发者文档一样：时间戳\nnonce\n方法\n路径和查询\n请求体，HMAC-SHA256 小写十六进制） */
export function quoteSigned(keyId, secret, path) {
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = randomUUID().replace(/-/g, '');
  const sig = createHmac('sha256', secret).update(`${ts}\n${nonce}\nGET\n${path}\n`).digest('hex');
  return req(QUOTE, path, { headers: { 'X-QK-Key': keyId, 'X-QK-Timestamp': ts, 'X-QK-Nonce': nonce, 'X-QK-Signature': sig } });
}
