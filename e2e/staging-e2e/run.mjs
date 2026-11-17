// 跑测试环境验收用例（做法 07）：node e2e/staging-e2e/run.mjs [用例文件 ...]（不写就跑这个目录下全部 *.test.mjs）
// 由 .github/workflows/staging-e2e.yml 运行。地址和令牌从环境变量来（GitHub 环境 staging-e2e 的密钥），不打印：
//   STAGING_URL（客户端域名）、STAGING_ADMIN_URL（后台域名，后台接口只在这个域名上）、STAGING_TEST_ADMIN_TOKEN（测试管理员令牌）、
//   STAGING_QUOTE_URL（报价对外子域名，可选；没有时报价对外的用例跳过，算"未验证"）
// 先确认连的是测试环境（健康检查 env=test）、测试管理员开着（后台域名的健康检查 testAdmin=true），否则一个用例都不跑。
// 结果只在屏幕上逐条（spec），不另存报告：仓库公开，运行日志谁都能看，所以不输出接口返回的内容。
// 用例进程只拿到它要的环境变量，不继承整个环境；令牌由 lib.mjs 读进去后从环境里删掉。
import { spawnSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = join(DIR, '..', '..');
const fail = (msg) => { console.error(`✗ ${msg}`); process.exit(1); };
const strip = (v) => String(v || '').trim().replace(/\/+$/, '');

const app = strip(process.env.STAGING_URL);
const admin = strip(process.env.STAGING_ADMIN_URL);
if (!app || !admin || !process.env.STAGING_TEST_ADMIN_TOKEN) fail('需要 STAGING_URL、STAGING_ADMIN_URL、STAGING_TEST_ADMIN_TOKEN（GitHub 环境 staging-e2e 的密钥）');
for (const [name, v] of [['STAGING_URL', app], ['STAGING_ADMIN_URL', admin], ['STAGING_QUOTE_URL', strip(process.env.STAGING_QUOTE_URL)]]) {
  if (!v) continue;
  let u;
  try { u = new URL(v); } catch { fail(`${name} 不是有效的地址`); }
  if (u.protocol !== 'https:') fail(`${name} 要是 https:// 开头`);
}

// 只认这个目录里的用例文件（名字只能是小写字母、数字、连字符）
const NAME = /^[a-z0-9-]+\.test\.mjs$/;
const args = process.argv.slice(2).map((f) => relative(DIR, join(ROOT, f)).replace(/\\/g, '/'));
for (const f of args) if (!NAME.test(f)) fail(`只能跑 e2e/staging-e2e/ 里的 *.test.mjs：${f}`);
const files = (args.length ? args : readdirSync(DIR).filter((f) => NAME.test(f)).sort()).map((f) => join(DIR, f));
if (!files.length) fail('没有用例文件');

const health = async (base) => {
  try { return await (await fetch(`${base}/api/health`, { signal: AbortSignal.timeout(20_000) })).json(); } catch { return null; }
};
const h = await health(app);
if (h?.env !== 'test') fail(`客户端域名健康检查的 env 不是 test（${h?.env ?? '没有响应'}），停止：只对测试环境跑`);
const ha = await health(admin);
if (ha?.env !== 'test') fail('后台域名健康检查的 env 不是 test，停止');
if (ha?.testAdmin !== true) fail('测试环境的测试管理员没开：服务器上运行 sudo trading gen-test-admin 隐藏输入令牌（和 GitHub 环境 staging-e2e 里的是同一串）');
console.log(`✓ 测试环境（提交 ${String(h.version || '未知').slice(0, 12)}），测试管理员已开；跑 ${files.length} 个文件`);

// GITHUB_RUN_ID、GITHUB_RUN_ATTEMPT 是运行号（不是密钥）：lib.mjs 拿它给造的数据打标记
const PASS = ['PATH', 'HOME', 'TMPDIR', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'STAGING_URL', 'STAGING_ADMIN_URL', 'STAGING_QUOTE_URL', 'STAGING_TEST_ADMIN_TOKEN'];
const env = Object.fromEntries(PASS.filter((k) => process.env[k] !== undefined).map((k) => [k, process.env[k]]));
const r = spawnSync(process.execPath, ['--test', '--test-concurrency=1', '--test-reporter=spec', ...files], { stdio: 'inherit', env });
process.exit(r.status ?? 1);
