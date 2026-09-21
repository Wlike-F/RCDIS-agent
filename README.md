# RCDIS Agent — 实验室科研经费管理智能体

RCDIS Agent 是一个面向**实验室科研经费管理**的业务型 Agent 系统：以确定性业务 Workflow 为内核，
以受治理的 LLM 编排层为交互入口，覆盖项目预算、支出登记、报销审批、审计留痕全流程，
并通过对话（Web SSE）与飞书（通知 / 审批卡片）两个通道提供服务。

> 它首先是一个**正确、可追溯、可审计的经费管理业务系统**，其次才是一个 Agent。
> 模型负责"理解意图、选择工具、组织语言"，**绝不**负责产生事实数据或自行执行高风险写操作。

---

## 1. 项目目标

### 1.1 要解决的问题
实验室科研经费管理长期依赖表格与口头流程，存在：预算口径不清、支出登记随意、
报销材料不齐、审批链路不透明、事后无法审计等问题。本项目把这些流程**结构化、线上化、可审计化**，
再用 Agent 降低使用门槛（自然语言查询 / 引导式录入 / 主动提醒）。

### 1.2 产品原则（贯穿全部设计）
| 原则 | 含义 |
|---|---|
| 正确性 | 所有金额 / 状态 / 余额来自 PostgreSQL，模型不编造数字 |
| 可追溯 | 每次创建 / 修改 / 删除 / 审批 / Agent 提案都写 `audit_log` |
| 可解释 | Agent 回答标注数据来源；工具调用过程对前端可见 |
| 保守写 | 高风险写操作必须"提案 → 人工确认 → 确定性执行" |
| 业务优先 | 是业务系统 + Agent 能力，不是自由聊天机器人 |

### 1.3 非目标
- 不做通用闲聊 / 写作 / 编程助手
- 不让模型直接拼 SQL、直接改库、直接绕过审批
- 不用 ChromaDB 等外部向量库作主存储（如需检索优先 pgvector）

---

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 后端框架 | Spring Boot 3.5 |
| Agent 框架 | Spring AI 1.1（ChatClient / @Tool / ToolContext / 流式） |
| ORM | MyBatis-Plus 3.5（逻辑删除 + 乐观锁 + 审计自动填充） |
| 数据库 | PostgreSQL 14+ |
| 流式通道 | SSE（`SseEmitter`，POST + fetch ReadableStream） |
| 前端 | Vue 3 + Pinia + Element Plus + Vite；Markdown 渲染 marked + DOMPurify |
| 集成 | 飞书开放平台（自建应用 / Webhook / 交互卡片 / WS 长连接回调） |
| 文档 | SpringDoc OpenAPI / Swagger UI |
| 安全 | Spring Security + JWT（URL 层角色授权） |

---

## 3. 功能总览

### 3.1 业务功能（确定性 Workflow）
- **科研项目**：项目建档、预算总额、状态管理；quickMode 自动建默认预算科目
- **预算科目**：分配 / 已用 / 冻结 / 可用额维护；登记即占用预算
- **支出管理**：登记 / 修改 / 作废（软删除 + 原因）、发票凭证图片上传
- **报销中心**：建单（可挂已有支出或快捷录新支出）/ 修改 / **撤回** / 提交 / 审批通过 / 驳回 /
  材料检查 / 一步报销（submitNow）；状态机 DRAFT→SUBMITTED→APPROVED/REJECTED，DRAFT/REJECTED 可作废
- **飞书集成**：提交 / 通过 / 驳回三场景通知卡片；审批交互卡片私聊定向下发；
  卡片按钮回调（WS 长连接或 HTTP）；审批人绑定与自审拦截
- **用户与 RBAC**：sys_user / sys_role / sys_user_role；ADMIN / APPROVER / RESEARCHER 三角色
- **模型供应商管理**：多供应商（OpenAI 兼容协议）DB 持久化、API 密钥 AES-GCM 加密、
  运行时新增 / 探活 / 模型清单拉取 / 设默认
- **审计留痕**：`@AuditOperation` AOP 统一记录 actor / action / before / after / reason / conversationId

### 3.2 Agent 功能
- **真流式对话**：SSE 逐 token 推送；前端 Markdown（含 GFM 表格）实时渲染
- **多轮记忆**：`chat_session` + `chat_message` 双表持久化；会话归属校验；按最近活跃排序
- **17 个工具**：13 只读 + 4 个确认门工具（见 §5.5），全部委托确定性 Service
- **提案 / 确认闭环**：写工具只生成 `agent_pending_action` 提案 + 推 `requires_confirmation`，
  用户点"确认执行"后才真正写库（见 §5.6）
- **基础计算工具**：日期解析 / 区间换算 / 金额求和 / 预算可用额，杜绝模型心算出错
- **开发者管理页**：工具清单（名称 / 类别 / 入参 / 是否需确认）实时反射展示

### 3.3 前端页面
对话工作台、总览、科研项目、支出管理、报销中心、模型供应商、飞书通知（发送记录 / 配置项双 tab）、
用户管理、开发者管理、登录。

---

## 4. 系统架构

### 4.1 后端分层（MVC 包结构）
```
com.rcdis.agent
├── controller     REST + SSE 端点，只做传输（Request/Response DTO）
├── service        业务用例接口；impl 承载事务与编排
├── mapper         MyBatis-Plus Mapper（复杂报表用显式 SQL）
├── entity         PostgreSQL 持久化对象（BaseEntity: 逻辑删除+审计字段）
├── vo / to / dto  视图对象 / 内部传输对象 / API 边界对象
├── agent          Spring AI 桥接层：ChatClient 工厂、工具、提示词加载、ToolContext
├── infrastructure 飞书客户端、模型供应商客户端、密钥加密、文件存储
├── config         Spring 配置、@ConfigurationProperties、安全、异步线程池
└── common         统一响应、全局异常、AOP 审计、上下文、工具类
```

### 4.2 关键横切能力
- **全局异常处理**：`BusinessException(code, message, status)` + `@RestControllerAdvice`
- **审计 AOP**：`@AuditOperation` 自动写 `audit_log`，原因来自 `AuditReasonProvider`
- **身份上下文**：JWT → `CurrentUserContextHolder`（ThreadLocal）→ 审计 / 归属校验
- **授权**：URL 层角色规则（`/api/model-providers/**`、`/api/users/**`、`/api/feishu/**` 限 ADMIN；
  报销 approve/reject 限 ADMIN|APPROVER）；SSE 的 ASYNC dispatch 单独放行
- **异步线程池**：`sseTaskExecutor`（SSE 推送，传播身份）、`notificationTaskExecutor`（飞书出站）

---

## 5. Agent 架构（核心）

### 5.1 范式选择：Governed Hybrid Agent（受治理混合 Agent）
**结论**：以**确定性 Workflow 为内核**、**LLM 作受治理编排层**的混合范式——
`Router 分诊 + ReAct 只读 + 提案-确认-执行（单步写）+ 限定式 Plan-and-Execute（批量报销提交）+ Human-in-the-loop 确认门`。

### 5.2 为什么这样选（理由）
| 领域特性 | 架构约束 | 范式取舍 |
|---|---|---|
| 财务数据错误代价高、部分不可逆 | 不能让 LLM 自由"行动" | **排除纯自主 ReAct**；写必须过确认门 / 审批流 |
| 业务规则复杂且已有确定性内核 | 规则不应由 LLM 重实现 | **Workflow 为内核**，LLM 只编排 / 填参 / 解释 |
| 可追溯 / 可审计是硬需求 | 每步要有记录、可复盘 | **排除黑盒自由循环**；task / pending_action / audit 留痕 |
| 天然存在审批人角色 | "人"应是一等节点 | **Human-in-the-loop**，而非事后补丁 |
| 模型不是事实来源 | 所有数字来自 DB | **tool-grounded**：ReAct 只读查库 |
| 任务以流程性为主 + 少量开放问答 | 流程用 workflow 引导 | **混合**，而非单一范式 |

一句话：**报销是"合规流程"，不是"开放创作"。流程骨架必须确定、可审计；
LLM 的价值是把自然语言意图接进这个骨架（理解、填参、查数、解释），而不是取代骨架。**

### 5.3 分层视图
```
L4 观测/评估   trace(工具链/耗时) + eval 回归集 + audit_log
L3 交互/确认   SSE 事件 + 确认卡片 + 飞书审批卡片      ← human-in-the-loop 门
L2 Agent 编排  Router 分诊
               ├ 读/问答/分析 → ReAct tool-calling（只读工具集）
               ├ 单步写      → 提案-确认-执行（已落地）
               └ 多步复合写  → 限定式 Plan-and-Execute + 整体确认 + task/step 状态机（已落地）
L1 能力/工具   只读查询工具 + 基础计算工具 +（规划）聚合SQL / OCR / RAG
L0 确定性内核  Service / 状态机 / 约束 / 编排层 / 审计        ← 绝不改动
```

### 5.4 意图 → 范式 决策表
| 意图 | 范式 | 状态 |
|---|---|---|
| 查余额 / 明细 / 审计 / 分析 | ReAct 只读 tool-calling | 已落地 |
| 登记 / 修改 / 作废 / 提交 / 建单（单步） | 提案-确认-执行 | 已落地 |
| "把项目中材料齐全的草稿报销单批量提交"（多步） | 限定式 Plan-and-Execute + 整体确认 | 已落地 |
| 拍发票 → 建支出（结构化抽取） | 专用多模态 Workflow 节点 | 规划中 |
| 政策 / 制度问答 | RAG 检索 + 引用回答 | 规划中 |
| 超阈 / 待审主动提醒 | 定时 Workflow + 推送 | 规划中 |

### 5.5 工具清单（17 个，全部委托确定性 Service，不碰 Mapper）
**只读（11）**
| 工具 | 作用 |
|---|---|
| query_project_budget | 项目预算总览 + 各科目分配/已用/冻结/可用 |
| list_projects / list_reimbursements | 项目 / 报销单列表 |
| check_reimbursement_materials | 报销材料齐备检查 |
| generate_reimbursement_summary | 报销单结构化汇总 |
| list_audit_logs | 审计留痕查询 |
| get_current_datetime / parse_date / date_range | 服务器时间 / 松散日期归一 / 周期区间换算 |
| sum_amounts / budget_remaining | 金额精确求和平均 / 可用额与执行率 |

**确认门工具（4，全部走提案-确认）**
| 工具 | 委托 Service |
|---|---|
| submit_reimbursement / create_reimbursement | ReimbursementApprovalService（编排层，含飞书通知） |
| plan_reimbursement_submissions / retry_reimbursement_plan | AgentTaskService（task/step 状态机，整体确认、部分失败与重试） |

### 5.6 写操作：提案 / 提交分离 + 确认闭环
```
LLM 调写工具 → 校验+解析 id+读 before 快照 → 插 agent_pending_action(PENDING)
             → SSE requires_confirmation（确认卡片）→ 返回"未执行，待确认"
用户点确认  → POST /api/chat/confirm → 重校验(归属/PENDING/未过期)
             → 调编排层 Service 真执行 → EXECUTED + audit_log
用户取消    → REJECTED；超时 → EXPIRED；执行失败 → FAILED
```
要点：LLM **只能提案、不能执行**；执行入口与 Web UI / 飞书回调**完全同一编排层**，避免副作用丢失。

### 5.7 多轮记忆
- `chat_session`：会话归属（user_id）、provider、消息计数、最近活跃时间
- `chat_message`：逐轮 user/assistant 原文 + 状态 + 模型信息；只把 `DONE` 的消息回灌模型
- 前端持久化 activeId + 按 updatedAt 倒序，刷新回到上次会话

### 5.8 SSE 事件契约
`start / token / tool_start / tool_result / requires_confirmation / error / done`
（前端 `stores/chat.ts` 已全量消费；token 逐字推送，done 携带 conversationId/provider/model）

### 5.9 系统提示词
外置于 `src/main/resources/prompts/agent-system.st`（中文），约束：业务边界、数据准则（不编造数字）、
高风险操作复述+确认、语言风格、安全合规、工具使用准则。启动时加载并缓存。

### 5.10 治理要点（踩坑沉淀）
- 工具运行在 Reactor 线程，**身份经 ToolContext 显式传递**，不依赖 ThreadLocal（否则审计记 anonymous）
- 写工具确认执行**必须走编排层**（如 `ReimbursementApprovalService`），否则飞书通知静默缺失
- SSE 完成属 ASYNC dispatch，需在 Security 中单独放行，否则连接不关闭
- 提案 / 确认 / 执行全链路留痕，满足审计硬需求

---

## 6. 数据模型概览（PostgreSQL，18 表）
业务：`research_project`、`budget_category`、`expense_record`、`reimbursement_order`、`reimbursement_item`
审计/通知：`audit_log`、`notification_outbox`、`notification_template`、`feishu_callback_event`、`feishu_approver`
模型：`model_provider`、`model_provider_model`
权限：`sys_user`、`sys_role`、`sys_user_role`
Agent：`chat_session`、`chat_message`、`agent_pending_action`

schema 参考：`docs/database-schema.sql`（幂等，可重复执行）

---

## 7. 目录结构
```
├── src/main/java/com/rcdis/agent   后端（见 §4.1）
├── src/main/resources
│   ├── application.yml             主配置（含 rcdis.agent / rcdis.ai / rcdis.feishu）
│   └── prompts/agent-system.st     Agent 系统提示词
├── frontend                        Vue 3 控制台
├── docs                            database-schema.sql / api.md 等
└── scripts                         init-postgres.ps1 / db-run.ps1 / 各类验证脚本
```

---

## 8. 本地开发

前置：JDK 17、Maven 3.9+、PostgreSQL 14+、Node 18+。

```powershell
# 1) 初始化数据库（先设 PGPASSWORD）
$env:PGPASSWORD = "your-postgres-password"
.\scripts\init-postgres.ps1

# 2) 启动后端
mvn spring-boot:run          # http://localhost:8080

# 3) 启动前端
cd frontend; npm install; npm run dev   # http://localhost:5173（代理 /api → 8080）

# 4) 测试
mvn test
```

Agent 行为回归集包含 50+ 条工具选择、确认门、事实一致性与安全案例。普通测试只校验数据集和断言引擎；连接真实模型、多供应商对比及报告生成方式见
[`docs/agent-eval.md`](docs/agent-eval.md)。

API 文档：`http://localhost:8080/swagger-ui.html`、`/v3/api-docs`
默认种子账号：`admin / admin123`（首次登录后请修改）

---

## 9. 配置要点
- 模型供应商：首启从 `rcdis.ai.providers` 种子入 `model_provider` 表，之后以 DB 为准；
  API 密钥 AES-GCM 加密存储（主密钥 `RCDIS_PROVIDER_SECRET_KEY`）
- 飞书：`rcdis.feishu.*`（enabled / client-type / app-id / app-secret / webhook / 回调模式等）
- Agent：`rcdis.agent.history-max-messages`、`stream-timeout-seconds`、
  `system-prompt-location`、`confirmation-ttl-minutes`
- 安全：`rcdis.security.auth-required`、JWT secret / TTL、种子用户

---

## 10. 演进路线图
**已落地**：真流式对话、双表多轮记忆、16 工具、提案-确认闭环、飞书审批、RBAC、开发者管理页、Markdown 渲染。

**规划中（按价值/风险排序）**：
1. 聚合 SQL 只读工具（预算执行率 / 超支预警 / 待审队列）——解决"管理员看不清经费"
2. 会话服务端化 REST（摆脱 localStorage，多设备 + 可审计）
3. 扩展限定式 Plan-and-Execute 的时间范围筛选与更多业务计划模板
4. Agent 可观测 trace + eval 回归集的 CI 基线与趋势报告
5. OCR / 多模态凭证抽取、RAG 政策检索、主动预警推送
