# RCDIS-agent 接口文档

## 1. 在线文档

项目接入 SpringDoc OpenAPI。

启动服务后可访问：

```text
Swagger UI: http://localhost:8080/swagger-ui.html
OpenAPI JSON: http://localhost:8080/v3/api-docs
```

## 2. 通用响应结构

普通 JSON API 使用统一响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {},
  "timestamp": "2026-08-29T20:30:00+08:00"
}
```

失败响应示例：

```json
{
  "success": false,
  "code": "REQUEST_VALIDATION_FAILED",
  "message": "message must not be blank",
  "data": null,
  "timestamp": "2026-08-29T20:30:00+08:00"
}
```

## 3. 健康检查

### GET `/api/health`

用途：检查服务是否启动。

响应 data：

```json
{
  "status": "UP",
  "application": "rcdis-agent",
  "time": "2026-08-29T20:30:00+08:00"
}
```

## 4. 认证

### POST `/api/auth/login`

用途：签发 JWT 访问令牌。

当前阶段使用开发用户配置，不接数据库用户表。

请求：

```json
{
  "username": "admin",
  "password": "admin"
}
```

响应 data：

```json
{
  "accessToken": "jwt-token",
  "tokenType": "Bearer",
  "expiresAt": "2026-08-31T12:00:00+08:00",
  "user": {
    "userId": "admin",
    "username": "admin",
    "tenantId": "default",
    "roles": ["ADMIN"]
  }
}
```

后续请求可带：

```text
Authorization: Bearer jwt-token
```

当前 `rcdis.security.auth-required=false` 时，JWT 解析生效但不强制所有 `/api/**` 登录。

## 5. Agent 对话

### POST `/api/chat`

用途：发送一条普通 Agent 消息。

请求：

```json
{
  "conversationId": "optional-conversation-id",
  "providerId": "dashscope",
  "message": "查询项目A试剂费还剩多少"
}
```

响应 data：

```json
{
  "conversationId": "generated-or-existing-id",
  "content": "RCDIS Agent skeleton is ready. Spring AI tool calling will be connected in the next milestone.",
  "providerId": "dashscope",
  "modelName": "qwen-plus"
}
```

### POST `/api/chat/stream`

用途：发送一条 Agent 消息，并使用 SSE 流式返回。

请求体同 `/api/chat`。

事件：

```text
start
token
done
error
```

示例：

```text
event:start
data:{"ok":true}

event:token
data:{"text":"..."}

event:done
data:{"conversationId":"...","content":"...","providerId":"dashscope","modelName":"qwen-plus"}
```

## 6. 模型供应商

模型供应商配置存放在 PostgreSQL 的 `model_provider` 与 `model_provider_model` 两张表中。
`application.yml` 里的 `rcdis.ai.providers` 只在首次启动时作为种子数据迁移入库（`builtin=1`），
之后一律以数据库为准，页面上的修改不会被启动流程覆盖。

API 密钥经 AES-256/GCM 加密后写入 `api_key_cipher`（格式 `v1:<iv>:<ciphertext>`），主密钥来自
环境变量 `RCDIS_PROVIDER_SECRET_KEY`。所有读接口只返回 `apiKeyConfigured` 与脱敏的
`apiKeyHint`（形如 `****cKSQ`），明文密钥既不回传前端，也不写入日志与审计快照。

### 接入协议

可用协议通过 `GET /api/model-providers/protocols` 查询。当前实现 `openai-compatible`，
自定义模型端点需要满足以下约定：

| 能力 | 方法与路径 | 说明 |
| --- | --- | --- |
| 对话补全 | `POST {baseUrl}{chatCompletionsPath}` | 默认 `/v1/chat/completions`，请求体 `{model, messages[], max_tokens, temperature, stream}` |
| 模型列表 | `GET {baseUrl}{modelsPath}` | 默认 `/v1/models`，响应 `{"data":[{"id":"..."}]}` 或裸数组 |
| 鉴权 | `Authorization: Bearer <apiKey>` | 本地免密钥端点（如 Ollama）可留空 |

`baseUrl` 只填服务根地址，不要带 `/v1`；若填入以 `/v1` 结尾的地址，系统会自动纠正为根地址，
避免拼出 `/v1/v1/chat/completions`。两个路径都可按供应商单独覆盖，以适配智谱
`/api/paas/v4/chat/completions` 这类非 `/v1` 版本的网关。

`anthropic` 与 `ollama-native` 已在协议目录中列出，但标记为 `supported=false`，暂不可选。
一套 `openai-compatible` 即可覆盖 OpenAI、DeepSeek、通义千问、Moonshot、智谱、SiliconFlow、
OpenRouter、Groq、Together、Ollama、vLLM、LM Studio、Xinference 与 One-API 类网关。

### GET `/api/model-providers`

用途：查询全部模型供应商及其模型列表。

响应 data：

```json
[
  {
    "id": 1,
    "providerId": "dashscope",
    "name": "DashScope Qwen",
    "protocol": "openai-compatible",
    "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode",
    "chatCompletionsPath": "/v1/chat/completions",
    "modelsPath": "/v1/models",
    "chatModel": "qwen-plus",
    "models": [
      {
        "id": 1,
        "modelName": "qwen-plus",
        "displayName": null,
        "defaultModel": true,
        "source": "MANUAL",
        "enabled": true,
        "version": 0
      }
    ],
    "enabled": true,
    "defaultProvider": true,
    "builtin": true,
    "apiKeyConfigured": true,
    "apiKeyHint": "****cKSQ",
    "timeoutSeconds": 30,
    "lastTest": {
      "status": "SUCCESS",
      "message": "模型 qwen-plus 对话验证通过，耗时 413 ms",
      "httpStatus": 200,
      "latencyMs": 413,
      "testedAt": "2026-09-02T15:36:41+08:00"
    },
    "version": 0
  }
]
```

`chatModel` 是该供应商的默认模型名，供对话请求在未显式指定模型时使用。
`lastTest` 为最近一次探活结果，未测试过时为 `null`。

### POST `/api/model-providers`

用途：新增自定义模型供应商。`providerId` 需以字母开头，创建后不可修改。

请求：

```json
{
  "providerId": "deepseek",
  "name": "DeepSeek",
  "protocol": "openai-compatible",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-...",
  "timeoutSeconds": 30,
  "enabled": true,
  "defaultProvider": false,
  "models": [
    { "modelName": "deepseek-chat", "defaultModel": true, "status": "ACTIVE" }
  ]
}
```

`apiKey` 是只写字段：可以提交，但不会出现在任何响应中。省略 `models` 表示暂不配置模型，
之后可用「拉取模型」从端点批量导入。

### PUT `/api/model-providers/{id}`

用途：修改供应商与其模型列表，需要携带 `version` 做乐观锁校验。

`apiKey` 留空表示保持原密钥不变；`clearApiKey=true` 表示清除已保存的密钥。
请求中省略 `models` 字段表示不改动模型列表；传空数组表示清空全部模型。
列表里未出现的既有模型会被软删除。

版本过期返回 `409 MODEL_PROVIDER_VERSION_CONFLICT`。

### DELETE `/api/model-providers/{id}`

用途：软删除供应商，其模型一并软删除。请求体需要 `reason` 与 `version`。

内置供应商不可删除（`400 MODEL_PROVIDER_BUILTIN_PROTECTED`），只能停用；
默认供应商不可删除（`400 MODEL_PROVIDER_DEFAULT_DELETE_REJECTED`），需先切换默认。

### POST `/api/model-providers/{id}/status`

用途：在 `ACTIVE` 与 `DISABLED` 之间切换。默认供应商不能被停用
（`400 MODEL_PROVIDER_DEFAULT_DISABLE_REJECTED`）。

### POST `/api/model-providers/{id}/set-default`

用途：把该供应商设为全局默认，原默认自动取消。已停用的供应商不能被设为默认
（`400 MODEL_PROVIDER_DISABLED_DEFAULT_REJECTED`）。

### POST `/api/model-providers/{id}/models/{modelId}/set-default`

用途：把某个模型设为该供应商的默认对话模型。已停用的模型不能被设为默认。

### POST `/api/model-providers/{id}/discover-models`

用途：调用端点的模型列表接口，拉取可用模型。

请求 `{"persist": false}` 只返回结果；`{"persist": true}` 会把尚未登记的模型以
`source=DISCOVERED`、`status=ACTIVE`、非默认的方式入库，便于后续筛选。
端点不可达时返回 `400 MODEL_PROVIDER_DISCOVERY_FAILED`，并带上探活得到的原因。

响应 data：

```json
{
  "providerId": "dashscope",
  "discoveredModels": ["qwen-plus", "qwen-max"],
  "persistedCount": 2,
  "skippedCount": 0,
  "message": "已发现 2 个模型，新增入库 2 个"
}
```

### POST `/api/model-providers/test`

用途：对端点发起**真实**请求，验证连通性、密钥与模型名，并把结果写回供应商记录。

先调用 `GET {baseUrl}{modelsPath}` 校验连通与鉴权并发现模型；`probeChat=true` 时再发一条
最小对话请求（`max_tokens=8`）校验模型名是否真的可用，会消耗极少量额度。

请求：

```json
{
  "providerId": "dashscope",
  "modelName": "qwen-plus",
  "probeChat": true
}
```

`providerId` 留空表示测试默认供应商；`modelName` 留空表示使用该供应商的默认模型。

响应 data：

```json
{
  "providerId": "dashscope",
  "configured": true,
  "enabled": true,
  "status": "SUCCESS",
  "message": "模型 qwen-plus 对话验证通过，耗时 413 ms",
  "success": true,
  "httpStatus": 200,
  "latencyMs": 413,
  "testedModel": "qwen-plus",
  "discoveredModels": ["qwen-plus"],
  "testedAt": "2026-09-02T15:36:41+08:00"
}
```

`status` 取值：`SUCCESS`、`AUTH_FAILED`（401/403，密钥错误或过期）、`NOT_FOUND`（404，
地址或路径错误，也包含模型名不存在）、`RATE_LIMITED`（429）、`SERVER_ERROR`（5xx）、
`CLIENT_ERROR`（其他 4xx）、`TIMEOUT`、`UNREACHABLE`（域名无法解析或连接被拒绝）、
`INVALID_RESPONSE`、`NOT_READY`（协议暂不支持探活）。`message` 已做脱敏与截断，不含密钥。

## 7. 科研项目

### GET `/api/projects`

用途：分页查询科研项目。

当前接口已经按 MVC 链路接入：

```text
ProjectController
  ↓
ResearchProjectService
  ↓
ResearchProjectServiceImpl
  ↓
ResearchProjectMapper
  ↓
research_project
```

请求参数：

```text
current=1
size=20
keyword=NSFC
status=ACTIVE
```

响应 data：

```json
{
  "current": 1,
  "size": 20,
  "total": 1,
  "pages": 1,
  "records": [
    {
      "id": 1,
      "projectCode": "NSFC-2026-001",
      "projectName": "Example Project",
      "principalInvestigator": "张老师",
      "fundingSource": "国家自然科学基金",
      "totalBudget": "500000.00",
      "startDate": "2026-01-01",
      "endDate": "2029-12-31",
      "status": "ACTIVE",
      "version": 0
    }
  ]
}
```

### GET `/api/projects/{id}`

用途：查询单个科研项目基础信息。

### POST `/api/projects`

用途：创建科研项目。

请求：

```json
{
  "projectCode": "NSFC-2026-001",
  "projectName": "Example Project",
  "principalInvestigator": "张老师",
  "fundingSource": "国家自然科学基金",
  "totalBudget": "500000.00",
  "startDate": "2026-01-01",
  "endDate": "2029-12-31",
  "status": "ACTIVE"
}
```

### PUT `/api/projects/{id}`

用途：更新科研项目基础信息。

请求：

```json
{
  "projectName": "Example Project Updated",
  "principalInvestigator": "张老师",
  "fundingSource": "国家自然科学基金",
  "totalBudget": "600000.00",
  "startDate": "2026-01-01",
  "endDate": "2029-12-31",
  "status": "ACTIVE",
  "version": 0
}
```

`version` 必须使用查询接口返回的当前版本，用于乐观锁并发控制。

### DELETE `/api/projects/{id}`

用途：软删除科研项目。

请求：

```json
{
  "reason": "项目录入错误",
  "version": 1
}
```

有未删除预算科目时，项目不能直接删除。

### GET `/api/projects/{projectId}/budget-categories`

用途：分页查询项目预算科目配置。

请求参数：

```text
current=1
size=20
keyword=材料
status=ACTIVE
```

### GET `/api/projects/{projectId}/budget-categories/{id}`

用途：查询单个预算科目配置。

响应 data：

```json
{
  "id": 10,
  "projectId": 1,
  "categoryCode": "MATERIAL",
  "categoryName": "材料费",
  "allocatedAmount": "100000.00",
  "usedAmount": "0.00",
  "frozenAmount": "0.00",
  "availableAmount": "100000.00",
  "status": "ACTIVE",
  "remark": "试剂、耗材等",
  "version": 0
}
```

### POST `/api/projects/{projectId}/budget-categories`

用途：创建项目预算科目配置。

请求：

```json
{
  "categoryCode": "MATERIAL",
  "categoryName": "材料费",
  "allocatedAmount": "100000.00",
  "status": "ACTIVE",
  "remark": "试剂、耗材等"
}
```

同一项目下，未删除预算科目的 `categoryCode` 不能重复；有效预算科目分配金额合计不能超过项目总预算。

### PUT `/api/projects/{projectId}/budget-categories/{id}`

用途：更新项目预算科目配置。

请求：

```json
{
  "categoryName": "材料费",
  "allocatedAmount": "120000.00",
  "status": "ACTIVE",
  "remark": "试剂、耗材等",
  "version": 0
}
```

### DELETE `/api/projects/{projectId}/budget-categories/{id}`

用途：软删除项目预算科目配置。

请求：

```json
{
  "reason": "预算科目录入错误",
  "version": 1
}
```

有已使用或已冻结金额时，预算科目不能删除。

## 8. 飞书通知

### GET `/api/feishu/config`

用途：读取服务端飞书通知配置状态。该接口只返回密钥是否配置和默认接收方脱敏值，不返回 app secret、webhook URL、签名密钥或 access token。

响应 data：

```json
{
  "enabled": true,
  "clientType": "app",
  "channel": "FEISHU_APP",
  "status": "READY",
  "message": "飞书通知配置可用。",
  "openApiBaseUrl": "https://open.feishu.cn",
  "appIdConfigured": true,
  "appSecretConfigured": true,
  "defaultReceiveIdConfigured": true,
  "defaultReceiveIdType": "chat_id",
  "maskedDefaultReceiveId": "oc_f****0c32",
  "webhookConfigured": false,
  "signingSecretConfigured": false,
  "maxAttempts": 3
}
```

### GET `/api/feishu/templates`

用途：返回当前系统内置的飞书通知模板，用于前端快速填充业务通知内容。

### GET `/api/feishu/notifications`

用途：分页查询飞书通知出箱记录。

请求参数：

```text
current=1
size=20
status=SENT
channel=FEISHU_APP
keyword=budget-warning
```

`status` 可选值：`PENDING`、`SENT`、`FAILED`。

`channel` 可选值：`FEISHU_WEBHOOK`、`FEISHU_APP`、`FEISHU_NOOP`。

### POST `/api/feishu/test-message`

用途：发送一条飞书机器人测试消息。

当前接口已经按 MVC 链路接入：

```text
FeishuController
  ↓
FeishuNotificationService
  ↓
FeishuNotificationServiceImpl
  ↓
notification_outbox
  ↓
FeishuBotClient
  ↓
FeishuWebhookBotClient / FeishuAppBotClient / NoopFeishuBotClient
```

请求：

```json
{
  "target": "webhook",
  "receiveIdType": "chat_id",
  "text": "RCDIS-agent 飞书通知测试",
  "idempotencyKey": "optional-idempotency-key"
}
```

可选请求头：

```text
X-User-Id: admin
X-User-Name: Admin
X-Tenant-Id: lab-a
X-Conversation-Id: conversation-id
```

响应 data：

```json
{
  "notificationId": 18,
  "idempotencyKey": "generated-or-existing-id",
  "messageType": "text",
  "target": "webhook",
  "status": "SENT",
  "duplicate": false,
  "sentAt": "2026-08-31T10:30:00+08:00"
}
```

服务端会先写入 `notification_outbox` 的 `PENDING` 记录，再调用飞书客户端。发送成功后记录更新为 `SENT`，发送失败后记录更新为 `FAILED` 并保存错误摘要。

相同 `idempotencyKey` 的重复请求不会重复投递，会直接返回已有通知记录，响应中的 `duplicate=true`。

当 `rcdis.feishu.enabled=false` 时，接口会走 `NoopFeishuBotClient`，只记录日志，不真实发送。

当 `rcdis.feishu.client-type=app` 时，`target` 表示飞书 `receive_id`，`receiveIdType` 支持飞书消息发送接口的 `chat_id`、`open_id`、`user_id`、`union_id`、`email` 等类型。

## 9. 审计日志

### GET `/api/audit-logs`

用途：分页查询审计日志。

请求参数：

```text
current=1
size=20
```

响应 data：

```json
{
  "current": 1,
  "size": 20,
  "total": 1,
  "pages": 1,
  "records": [
    {
      "id": 1,
      "actor": "admin",
      "tenantId": "default",
      "action": "SEND_TEST_FEISHU_MESSAGE",
      "targetType": "FEISHU_BOT",
      "targetId": null,
      "beforeSnapshot": "{\"method\":\"...\"}",
      "afterSnapshot": "{\"status\":\"SENT\"}",
      "reason": null,
      "source": "API",
      "conversationId": null,
      "createdAt": "2026-08-31T12:00:00+08:00"
    }
  ]
}
```

## 10. 后续接口规划

后续将补充：

- `GET /api/projects/{id}/budget`
- `POST /api/expenses`
- `GET /api/expenses`
- `PUT /api/expenses/{id}`
- `DELETE /api/expenses/{id}`
- `POST /api/reimbursements`
- `POST /api/reimbursements/{id}/check-materials`
- `POST /api/feishu/events`
