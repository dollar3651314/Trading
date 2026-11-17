# Trading：给编程智能体和新成员的入口（AGENTS）
这个文件给 Claude Code 以外的编程智能体读取，内容和 `CLAUDE.md` 相同。

## 开始任何工作前
1. 读《[项目地图](项目地图.md)》：项目是什么、代码怎么分层、环境、不能破坏的约定。
2. 要改某个功能或排查某个问题时，在《[项目索引](项目索引.md)》里找对应的文件，再去读代码。
3. 需求和方案在公司内部文档。

## 每次改动都要做
- 在同一个提交里更新《项目地图》和《项目索引》；`node scripts/check-project-index.mjs` 会检查，CI 也会跑。
- 开发分支 PR 到 `staging`；`staging` → `main` 用 merge commit，合完把 `main` 合回 `staging`。
- PR 的 CI（`.github/workflows/ci.yml`，汇总检查 `ci-ok`）必须通过才能合并。
- 密钥、密码、助记词、私钥、API Key 不进仓库。
