# TravelPal — 前端设计指南

> 本文件记录了 React 前端的设计 Token、组件约定、和代码规范。
> AI 协作时请自动遵循以下约定。

## 设计调性

- **核心理念：** "以地图和旅程为灵感的探索式体验"
- **避免色彩：** ❌ 蓝白 / 蓝紫渐变 / 浅蓝气泡 / 白色毛玻璃
- **特质方向：** 温润大地色系 + 旅行手账质感 + 结构化行程卡片

## 设计 Token

### 色彩体系

| Token | 色值 | 用途 |
|-------|------|------|
| `terracotta-500` | `#C6663E` | 主色，品牌色，按钮/链接 |
| `charcoal-900` | `#2B2B2B` | 最深色，正文 |
| `sage-400` / `500` | `#8BA888` / `#6A8A6B` | 辅助色，成功/完成状态 |
| `mustard-400` / `500` | `#D4A84B` / `#BD8F39` | 强调色，天气图标/亮点 |
| `warm-white` | `#F5F0EB` | 页面背景 |
| 暗色背景 | `#1A1A1A` | `@media prefers-color-scheme: dark` |

### 字体

- **标题/Display：** `Playfair Display`（衬线体，旅行手记气质）
- **正文：** `Inter`（干净无衬线）

### 签名元素

- **折角信纸卡片**：`.corner-fold` — 白底圆角 + 左上微折角阴影，用于行程卡片
- **连接线圆点步骤条**：`.step-dot` + `.step-line` — 工作流节点状态指示器
- **打字光标**：`.cursor-blink` — 流式输出时的闪烁光标

## 组件库约定

- 使用 `shadcn/ui` 组件（`npx shadcn@latest add <component>` 安装）
- 统一使用 `cn()` 工具函数合并类名（`clsx` + `tailwind-merge`）
- 图标使用 `lucide-react`
- CSS 使用 Tailwind CSS v4 的 `@theme` 自定义 Token

## 代码规范

- TypeScript 严格模式
- 组件使用 `export function` 命名导出
- Props 接口定义在组件文件同一目录
- Hooks 使用 `use` 前缀，放在 `src/hooks/`
- 类型定义放在 `src/types/`
