#!/usr/bin/env node
// 品牌和内部信息检查（仓库公开；照搬前身项目的品牌检查并扩展）。两种用法：
//   1. 前端构建产物（在 web/client、web/admin 或 web/site 目录下，npm run build 之后）：node ../../scripts/check-brand.mjs dist
//      检查产物里所有文本文件。产物会整个发到客户的浏览器，注释、代码里的内部名字出现在产物里也算。
//   2. 整个仓库（仓库根目录，CI 的 checks job）：node scripts/check-brand.mjs --repo
//      检查 git 跟踪的全部文本文件：不能有前身项目的品牌名、品牌域名、前身项目的镜像仓库地址。
// 后端发给客户的文字（通知模板、代码里的固定文字）另由后端测试 BrandTextTest 检查。
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

// 拼起来写，免得本文件自己命中
const FORBIDDEN = [
  ['旧品牌名', new RegExp('fal' + 'con\\s*x', 'i')],
  ['旧品牌名', new RegExp('qui' + 'ck\\s*come', 'i')],
  ['前身项目的镜像地址', new RegExp('ghcr\\.io/' + 'dollar3651314/' + 'fal', 'i')],
];
// 例外：公司工作台上报脚本的默认上报地址（第 0 批骨架就有，不属于前身项目；要不要改成只读变量由负责人定）
const ALLOW = new Set(['scripts/company-report.mjs']);
const TEXT = /\.(js|mjs|cjs|ts|tsx|css|html|webmanifest|json|svg|txt|map|md|java|xml|yml|yaml|sql|properties|sh|csv|conf|template|example)$/i;

const args = process.argv.slice(2);
const repo = args[0] === '--repo';
const root = path.resolve(repo ? '.' : args[0] ?? 'dist');
if (!fs.existsSync(root)) {
  console.error(`找不到目录 ${root}（检查构建产物前先 npm run build）`);
  process.exit(2);
}
const walk = (d) =>
  fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(d, e.name);
    return e.isDirectory() ? walk(p) : TEXT.test(p) ? [p] : [];
  });
const files = repo
  ? execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter((f) => f && TEXT.test(f) && !ALLOW.has(f)).map((f) => path.join(root, f))
  : walk(root);

const hits = [];
for (const f of files) {
  if (!fs.existsSync(f)) continue;
  const text = fs.readFileSync(f, 'utf8');
  for (const [what, re] of FORBIDDEN) {
    const m = text.match(re);
    if (m) {
      const at = m.index ?? 0;
      hits.push(`${path.relative(root, f)}（${what}）：…${text.slice(Math.max(0, at - 40), at + 40).replace(/\s+/g, ' ')}…`);
    }
  }
}
if (files.length === 0) {
  console.error('没有找到要检查的文本文件（目录写错了？）');
  process.exit(2);
}
if (hits.length > 0) {
  console.error(`有 ${hits.length} 处不该出现的品牌名或内部信息：`);
  for (const h of hits.slice(0, 30)) console.error(`  ${h}`);
  process.exit(1);
}
console.log(`通过：${files.length} 个文件里没有旧品牌名和前身项目的内部信息`);
