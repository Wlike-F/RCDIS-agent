# RCDIS Agent 前端控制台

RCDIS-agent 的 Web 管理控制台。基于 Vue 3 + TypeScript + Vite + Element Plus 构建，
覆盖 Agent 对话（SSE 流式）、科研项目、模型供应商管理与飞书通知测试。

## 技术栈

- Vue 3.5（Composition API + `<script setup>`）
- TypeScript（strict 模式）
- Vite 6
- Element Plus 2.x + @element-plus/icons-vue（全站仅使用图标库，不使用表情符号）
- Pinia / Vue Router / Axios / Sass

## 快速开始

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器已配置代理：`/api` 默认转发到
`http://localhost:8080`（Spring Boot 后端），可通过环境变量 `VITE_PROXY_TARGET` 覆盖。

其他命令：

```bash
npm run build     # vue-tsc 类型检查 + 生产构建，输出到 dist/
npm run preview   # 本地预览构建产物
```

环境变量参考 `.env.example`：

- `VITE_API_BASE_URL`：浏览器直连后端地址，留空表示走同源代理
- `VITE_PROXY_TARGET`：开发代理目标，默认 `http://localhost:8080`

## 功能页面

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| 总览 | `/` | 项目数、经费总额、可用模型、服务健康度四张指标卡；项目经费总览与模块建设进度 |
| 对话工作台 | `/chat` | SSE 流式对话，支持 `start / token / tool_start / tool_result / requires_confirmation / error / done` 全量事件渲染 |
| 科研项目 | `/projects` | 项目列表、关键词与状态筛选、详情抽屉 |
| 支出管理 | `/expenses` | 支出登记（占用预算余额校验，表单内嵌审计备注直接提交）、修改、软删除（作废必填原因），修改/作废走二次确认，全部写入审计留痕，数据以 PostgreSQL 为准 |
| 报销中心 | `/reimbursements` | 关联支出单据生成报销单（单号 `R-日期-序号`）、服务端材料完整性检查（缺失阻断 / 提醒不阻断）、提交/审批/驳回状态机，全部写入审计留痕，数据以 PostgreSQL 为准 |
| 模型供应商 | `/providers` | 供应商列表、启停、设为默认、连通性测试、本地新增与编辑 |
| 飞书通知 | `/feishu` | 服务端配置状态、通知模板、测试发送、幂等键管理、notification_outbox 出箱历史 |

支出管理已接入后端 `/api/expenses`（服务端分页与筛选、余额同事务扣减、乐观锁、审计落库），审计留痕读取 `/api/audit-logs`。报销中心已接入后端 `/api/reimbursements`（服务端分页与筛选、状态机后端强制校验、材料检查两级结论：缺失阻断提交、提醒不阻断）；预算占用发生在支出登记时，审批通过仅将关联支出置为已报销。

## 对话工作台实现要点

- 后端流式接口为 POST `/api/chat/stream`，原生 EventSource 不支持 POST，
  因此基于 `fetch` + `ReadableStream` 实现了 SSE 解析器（`src/api/chat.ts`）。
- 高风险操作会渲染独立的确认卡片（操作类型、目标、变更前后、原因、影响范围），
  确认/取消动作提交到 `POST /api/chat/confirm`；后端确认接口未上线时给出明确提示，不执行操作。
- 会话与消息保存在浏览器 localStorage，仅用于原型演示，不作为业务事实来源。
- 助手回复中的工具调用以可折叠时间线展示，入参与结果以等宽字体渲染。

## 模型供应商管理约定

遵循项目规范（AGENTS.md）第一阶段要求：

- 服务端配置（`application.yml`）的供应商只读展示元数据，密钥仅显示「已配置/未配置」状态，不回传明文。
- 「新增供应商」写入浏览器本地（不含密钥）；密钥只保存在当前页面会话内存中，刷新即清除。
- 连通性测试：服务端配置走 `POST /api/model-providers/test`；本地新增供应商直接探测
  `{baseUrl}/models`，浏览器跨域受限时给出明确提示。

## 目录结构

```text
src/
  api/          axios 封装、统一响应解包、SSE 流式解析、请求上下文头
  stores/       Pinia：chat / providers / projects / app
  layouts/      主框架（侧栏 + 顶栏 + 内容区）
  components/   通用组件与聊天组件（MessageItem / ToolCallRow / ConfirmationCard）
  views/        五大业务页面
  styles/       设计令牌与 Element Plus 主题覆盖
  utils/        格式化、存储、业务常量
```

## 设计说明

- 视觉方向为「财务账本」质感：浅色侧栏 + 极客蓝主色（#2F54EB）激活胶囊，纸面浅灰内容区叠加
  极淡的坐标纸网格，金额与编号统一使用等宽字体 + 表格数字（tabular-nums）。
- 每个页面采用「英文小写字符 kicker + 中文主标题」的编辑式排版层级。
- 所有状态色（成功/警告/危险/信息）已在 `styles/index.scss` 中对齐 Element Plus
  CSS 变量体系，可直接扩展新页面。
