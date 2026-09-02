# RCDIS-agent 初步开发文档

## 1. 项目定位

RCDIS-agent 是一个面向实验室科研经费管理的小规模业务 Agent 系统。

它不是个人记账工具，也不是通用聊天机器人。项目核心目标是把实验室经费相关的结构化业务流程接入 Agent 能力，让用户可以通过自然语言完成查询、登记、材料检查、提醒和审批确认，同时保证数据准确、过程可追溯、关键操作可审计。

第一阶段先做小规模、单实验室、单团队可用版本。

## 2. 已确认技术栈

```text
Java 17
Spring Boot 3.x
Spring AI
MyBatis-Plus
PostgreSQL
SSE
Lombok
Feishu bot integration
```

数据库选择 PostgreSQL 作为业务主库。

ChromaDB 不作为主库。后续如果需要 RAG，优先考虑 PostgreSQL + pgvector。只有当文档检索规模扩大、向量服务需要独立部署时，再考虑 ChromaDB。

## 3. 第一阶段目标

MVP 目标是做出一个可靠的实验室经费管理 Agent 后端，先保证核心业务闭环。

第一阶段必须覆盖：

- 项目经费基础信息管理
- 预算科目管理
- 支出登记
- 支出查询
- 经费余额查询
- 支出修改与软删除
- 操作审计日志
- SSE 流式对话接口
- Spring AI 工具调用
- 模型供应商配置
- 飞书机器人通知基础能力

第一阶段不做：

- 多 Agent 协作
- 复杂工作流引擎
- 全自动审批
- 多租户 SaaS
- 大规模 RAG 知识库
- OCR 自动识别完整闭环
- 财务系统深度集成

## 4. 总体架构

系统按业务应用优先、Agent 能力内嵌的方式设计。

```text
Frontend
  ↓
Spring Boot Controller
  ↓
Application Service
  ↓
Domain Service / Policy
  ↓
Persistence / External Integration

Agent Path:

User Message
  ↓
Chat Controller / SSE Controller
  ↓
Agent Application Service
  ↓
Spring AI ChatClient
  ↓
Typed Tools
  ↓
Domain Services
  ↓
PostgreSQL
```

核心原则：

- Controller 不写业务规则。
- Agent 不直接操作数据库。
- LLM 不作为事实来源。
- 所有经费事实来自 PostgreSQL 或已校验文件。
- 写操作必须走应用服务和审计日志。
- 高风险操作必须确认。

## 5. 推荐包结构

```text
com.rcdis.agent
  ├── R c d i s Agent Application
  ├── controller
  │   ├── ChatController
  │   ├── ModelProviderController
  │   ├── ProjectController
  │   ├── ExpenseController
  │   └── ReimbursementController
  ├── application
  │   ├── AgentApplicationService
  │   ├── ExpenseApplicationService
  │   ├── BudgetApplicationService
  │   ├── ReimbursementApplicationService
  │   └── ModelProviderApplicationService
  ├── agent
  │   ├── ChatClientFactory
  │   ├── ModelProviderRegistry
  │   ├── AgentPromptFactory
  │   ├── AgentToolConfiguration
  │   └── tools
  ├── domain
  │   ├── project
  │   ├── budget
  │   ├── expense
  │   ├── reimbursement
  │   ├── approval
  │   └── audit
  ├── persistence
  │   ├── entity
  │   ├── mapper
  │   └── repository
  ├── infrastructure
  │   ├── feishu
  │   ├── file
  │   ├── llm
  │   └── notification
  └── config
```

实际落地时可以比这个更小，但边界不要混。

## 6. 核心业务模型

第一版建议先定义这些核心表。

### 6.1 research_project

科研项目表。

字段建议：

- id
- project_code
- project_name
- principal_investigator
- funding_source
- total_budget
- start_date
- end_date
- status
- created_at
- updated_at
- created_by
- updated_by
- deleted
- deleted_at
- deleted_by

### 6.2 budget_category

预算科目表。

字段建议：

- id
- project_id
- category_code
- category_name
- allocated_amount
- used_amount
- frozen_amount
- created_at
- updated_at

### 6.3 expense_record

支出记录表。

字段建议：

- id
- project_id
- budget_category_id
- amount
- expense_date
- vendor
- description
- invoice_no
- reimbursement_id
- status
- deleted_at
- delete_reason
- version
- created_at
- updated_at

金额统一用 `BigDecimal`。

### 6.4 reimbursement

报销单表。

字段建议：

- id
- project_id
- reimbursement_no
- applicant
- total_amount
- status
- submitted_at
- approved_at
- created_at
- updated_at

### 6.5 attachment

附件表。

字段建议：

- id
- owner_type
- owner_id
- file_name
- file_path
- content_type
- file_size
- checksum
- extracted_text
- created_at

### 6.6 audit_log

审计日志表。

字段建议：

- id
- actor
- action
- target_type
- target_id
- before_snapshot
- after_snapshot
- reason
- source
- conversation_id
- created_at

审计日志是业务事实的一部分，不能只依赖应用日志。

### 6.7 feishu_message_log

飞书消息日志表。

字段建议：

- id
- direction
- message_type
- target
- payload_snapshot
- status
- idempotency_key
- external_message_id
- error_message
- created_at

## 7. Agent 能力边界

Agent 第一阶段只做业务入口和辅助判断，不做最终事实裁决。

Agent 可以做：

- 解释用户经费问题
- 调用工具查询预算
- 调用工具登记支出
- 调用工具检查报销材料
- 生成报销摘要
- 发起确认请求
- 通过飞书发送提醒

Agent 不可以做：

- 编造预算余额
- 编造报销政策
- 绕过确认直接提交高风险操作
- 直接拼接 SQL
- 直接修改数据库
- 直接读取或暴露密钥

## 8. Spring AI 工具设计

第一阶段建议定义这些 typed tools：

```text
query_project_budget
record_expense
update_expense
delete_expense
check_reimbursement_materials
generate_reimbursement_summary
send_feishu_notification
list_audit_logs
```

每个 tool 只做一件事。

工具入参必须是明确 DTO，不接受随意 Map。

示例：

```java
public record QueryProjectBudgetRequest(
        Long projectId,
        String projectCode,
        String categoryName
) {
}
```

工具返回值也必须结构化，最终自然语言表达交给模型组织。

## 9. 高风险操作策略

这些操作必须确认：

- 新增支出
- 修改支出金额
- 修改支出日期
- 修改所属项目
- 修改预算科目
- 删除或作废支出
- 提交报销单
- 发送审批通知
- 调整预算额度

确认信息至少包含：

- 操作类型
- 目标对象
- 修改前内容
- 修改后内容
- 操作原因
- 影响范围

确认后必须写入 `audit_log`。

## 10. 模型供应商管理

模型供应商是产品能力，不应该写死在代码里。

第一阶段可以放在 `application.yml`：

```yaml
rcdis:
  ai:
    default-provider: deepseek
    providers:
      deepseek:
        type: openai-compatible
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        chat-model: deepseek-chat
      local-ollama:
        type: openai-compatible
        base-url: http://localhost:11434/v1
        api-key: ${OLLAMA_API_KEY:local}
        chat-model: qwen2.5
```

后续前端需要提供模型供应商设置页面。

页面能力：

- 查看供应商列表
- 新增供应商
- 编辑供应商元数据
- 启用或停用供应商
- 设置默认供应商
- 测试连通性
- 为当前会话选择模型

前端不显示明文 API Key。

第一阶段不建议让前端直接改 `application.yml`。可以先做配置读取和只读展示，后续再迁移到 PostgreSQL。

## 11. SSE 事件设计

聊天和长流程任务使用 SSE。

推荐事件：

```text
start
token
tool_start
tool_result
requires_confirmation
notification_sent
error
done
```

典型流程：

```text
用户：查一下项目 A 试剂费还剩多少

start
tool_start: query_project_budget
tool_result: verified budget data
token: 根据账本记录...
done
```

涉及写操作时：

```text
用户：给项目 A 记一笔试剂 860 元

start
tool_start: prepare_record_expense
requires_confirmation

用户确认后：

tool_start: record_expense
tool_result
token
done
```

## 12. 飞书机器人设计

飞书机器人属于基础设施集成，不是数据源。

第一阶段用途：

- 经费余额预警
- 报销材料缺失提醒
- 审批确认通知
- 每日或每周经费摘要
- Agent 流程状态通知

关键要求：

- webhook、secret、token 不进源码
- 入站回调必须验签
- 回调处理必须幂等
- 出站和入站消息都要记录日志表
- 不默认往群里发敏感发票图片、银行卡、身份证等信息

## 13. 日志规范

应用日志统一使用 Lombok `@Slf4j`。

禁止：

```text
System.out.println
System.err.println
printStackTrace
```

推荐：

```java
log.atInfo()
        .addKeyValue("projectId", projectId)
        .addKeyValue("expenseId", expenseId)
        .log("Expense recorded");
```

外部 API 调用失败必须记录：

- provider
- endpoint
- statusCode
- requestId
- sanitizedResponseBody

不能记录：

- API Key
- 飞书签名密钥
- access token
- 银行卡号
- 身份证号
- 完整发票影像

## 14. API 初步规划

### 14.1 Agent 对话

```text
POST /api/chat
POST /api/chat/stream
POST /api/chat/confirm
GET  /api/conversations/{id}
```

### 14.2 项目与预算

```text
GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PUT    /api/projects/{id}
GET    /api/projects/{id}/budget
POST   /api/projects/{id}/budget-categories
```

### 14.3 支出

```text
GET    /api/expenses
POST   /api/expenses
GET    /api/expenses/{id}
PUT    /api/expenses/{id}
DELETE /api/expenses/{id}
```

删除默认软删除，并要求删除原因。

### 14.4 报销

```text
GET  /api/reimbursements
POST /api/reimbursements
GET  /api/reimbursements/{id}
POST /api/reimbursements/{id}/submit
POST /api/reimbursements/{id}/check-materials
```

### 14.5 模型供应商

```text
GET  /api/model-providers
POST /api/model-providers/test
POST /api/model-providers/default
```

第一阶段可以先做只读列表和连接测试。

### 14.6 飞书

```text
POST /api/feishu/events
POST /api/feishu/test-message
```

## 15. 第一阶段里程碑

### Milestone 1: 工程骨架

- 创建 Spring Boot 3 项目
- 接入 PostgreSQL
- 接入 MyBatis-Plus
- 接入 Lombok
- 建立基础包结构
- 配置统一异常处理
- 配置统一日志格式

### Milestone 2: 经费基础数据

- research_project 表
- budget_category 表
- expense_record 表
- audit_log 表
- 项目与预算 CRUD
- 支出登记与查询

### Milestone 3: Agent 接入

- Spring AI ChatClient
- 模型供应商配置读取
- typed tools
- Agent prompt
- `/api/chat`
- `/api/chat/stream`

### Milestone 4: 风险确认与审计

- 确认态建模
- 高风险操作拦截
- 支出修改审计
- 支出软删除审计
- SSE `requires_confirmation`

### Milestone 5: 飞书机器人

- 飞书配置
- 出站通知
- 入站回调验签
- 幂等处理
- 消息审计日志

### Milestone 6: 报销材料检查

- 附件上传
- 报销单基础模型
- 材料完整性检查
- 报销摘要生成

## 16. 推荐开发顺序

建议按这个顺序推进：

```text
1. Spring Boot 工程初始化
2. PostgreSQL + MyBatis-Plus 配置
3. 核心表结构和实体
4. 项目/预算/支出基础 CRUD
5. 审计日志
6. Spring AI ChatClient
7. Agent tools
8. SSE 流式接口
9. 高风险确认流程
10. 飞书机器人
11. 报销材料检查
12. 前端管理页面
```

不要先做复杂前端。
不要先做多 Agent。
不要先做 RAG。

## 17. 当前关键决策

已经确认：

- 使用 Java 技术栈。
- 使用 Spring AI，不从 0 写 Agent loop。
- 使用 PostgreSQL 作为业务主库。
- 使用 MyBatis-Plus，不使用 JPA。
- 使用 SSE 做流式输出。
- 使用 Lombok `@Slf4j`，不用 `System.out`。
- 接入飞书机器人。
- 模型供应商前期放 `application.yml`，后续可迁移数据库。

暂未确认：

- 前端技术栈
- 是否使用 Docker Compose
- 是否使用 Flyway/Liquibase 管理数据库迁移
- 是否使用 Testcontainers
- 是否将 `rcdis.security.auth-required` 切换为默认强制鉴权
- 用户权限模型
- 飞书是 webhook bot 还是企业自建应用
- 文件存储使用本地、MinIO 还是对象存储

## 18. 风险点

### 18.1 Agent 幻觉

经费数据必须来自工具结果。
Prompt 中必须明确禁止编造。
回复中要区分“已查询到账本”和“需要人工确认”。

### 18.2 金额一致性

支出新增、修改、删除必须同步预算已用金额。
这些操作必须在同一个数据库事务内完成。

### 18.3 审计缺失

财务相关系统不能只依赖日志。
每个业务写操作都要有审计记录。

### 18.4 飞书误触发

飞书回调可能重复投递。
必须做幂等键。

### 18.5 配置泄露

模型 API Key 和飞书密钥不能进入源码和前端响应。

## 19. MVP 验收标准

第一版可验收标准：

- 可以创建科研项目和预算科目。
- 可以登记支出并扣减预算余额。
- 可以查询项目总预算、已用金额、剩余额度。
- 可以查询某个预算科目的支出明细。
- 可以通过 Agent 问自然语言问题并拿到真实数据库结果。
- 可以通过 SSE 看到流式输出。
- 修改和删除支出必须写审计日志。
- 删除支出为软删除，且必须填写原因。
- 飞书可以发送一条预算预警测试消息。
- 模型供应商配置不写死在代码里。

## 20. 下一步

下一步建议直接初始化 Spring Boot 项目骨架，并优先完成：

```text
pom.xml
application.yml
基础包结构
PostgreSQL 连接配置
MyBatis-Plus 配置
Lombok 配置
统一响应模型
统一异常处理
```

工程骨架完成后，再进入数据库建模和第一批业务 API。
