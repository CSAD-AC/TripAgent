<!--
提交 PR 前请先阅读本模板。
标题建议遵循 Conventional Commits, 例如: feat(agent-core): 新增 xxx / fix(mcp-server): 修复 xxx / chore(deps): 升级 xxx。
勾选框请统一保持 "- [ ]" 语法, 勾选时把方括号内的空格换成小写字母 x; 不适用的条目可保留未勾选并说明原因。
-->

## 变更说明

<!-- 简要说明这个 PR 做了什么、为什么需要它。涉及行为变更时请补充前后对比。 -->

## 变更类型

- [ ] 功能 (feat)
- [ ] 修复 (fix)
- [ ] 重构 (refactor)
- [ ] 文档 (docs)
- [ ] CI 与构建 (ci / build)
- [ ] 依赖升级 (chore(deps))

## 影响模块

- [ ] agent-core
- [ ] mcp-server
- [ ] frontend
- [ ] docs (README / dev-docs / CLAUDE.md)

## 自检清单

- [ ] 本地 `mvn compile` 与 `mvn test` 通过 (在 TripAgent/ 目录下执行)
- [ ] 前端 `npm run build` 通过 (改动 frontend/ 时必须)
- [ ] 新增或修改的代码、注释、字符串中无中文标点, 统一使用 ASCII 标点
- [ ] 未提交 `.env` 或任何密钥 (API Key 只保留在本地 `.env`, 仓库里只更新 `.env.example`)
- [ ] 接口或 SSE 协议有变更时, 已同步更新 README / dev-docs 中的协议说明
- [ ] 已自测本次改动涉及的页面或接口, 无未处理的控制台报错

## 关联 issue

- Closes #

<!--
没有对应 issue 时填写 "无"。
关联多个 issue 时逐行书写: Closes #12 / Closes #34。
-->

## 补充说明

<!-- 截图、关键日志(traceId)、性能影响、回滚方式等。 -->
