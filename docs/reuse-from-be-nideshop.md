# be-nideshop 复用评估

来源会话：`codex://threads/01a04d74-a84b-7e60-9477-9f199843a883`

旧项目路径：`C:\Users\19577\Desktop\Neu\neusoft\be-nideshop`

## 已确认可读取

已读取旧项目中与飞书和基础架构相关的核心文件：

- `src/main/java/neusoft/ihrss/cloud/service/FeishuService.java`
- `src/main/java/neusoft/ihrss/cloud/service/FeishuCommandService.java`
- `src/main/java/neusoft/ihrss/cloud/comp/RestComp.java`
- `src/main/java/neusoft/ihrss/cloud/controller/BusController.java`
- `src/main/java/neusoft/ihrss/cloud/interceptor/NutzInterceptor.java`
- `src/main/java/neusoft/ihrss/cloud/interceptor/LoginUserMethodArgumentResolver.java`
- `src/main/java/neusoft/ihrss/cloud/interceptor/CurrentWxUserMethodArgumentResolver.java`
- `src/main/java/neusoft/ihrss/cloud/controller/advice/CloudControllerAdvice.java`
- `src/main/java/neusoft/entity/BaseEntity.java`

## 迁移原则

旧项目的价值主要是工程分层和职责切分，不适合逐字复制。

RCDIS-agent 使用 Spring Boot 3、MyBatis-Plus 和 PostgreSQL，因此迁移采用同职责重写：

- Nutz DAO 拦截器迁移为 MyBatis-Plus `MetaObjectHandler`
- Feishu 发送入口迁移为 `FeishuBotClient`
- 登录用户解析迁移为 Spring MVC `HandlerMethodArgumentResolver`
- 通用拦截迁移为 `HandlerInterceptor`
- 横切审计迁移为 Spring AOP `@AuditOperation`
- 旧项目大而全的 `RestComp` 不整体复制，只保留外部系统客户端集中封装的思想

## 已落地模块

### 飞书通知客户端

新项目文件：

- `src/main/java/com/rcdis/agent/infrastructure/feishu/FeishuBotClient.java`
- `src/main/java/com/rcdis/agent/infrastructure/feishu/FeishuWebhookBotClient.java`
- `src/main/java/com/rcdis/agent/infrastructure/feishu/NoopFeishuBotClient.java`
- `src/main/java/com/rcdis/agent/infrastructure/feishu/FeishuSignSupport.java`
- `src/main/java/com/rcdis/agent/infrastructure/feishu/dto/FeishuWebhookRequest.java`
- `src/main/java/com/rcdis/agent/infrastructure/feishu/dto/FeishuWebhookResponse.java`

当前能力：

- 支持飞书自定义机器人文本消息
- 支持飞书自建应用机器人文本消息
- 支持应用凭证获取并缓存 `tenant_access_token`
- 支持 webhook 签名
- 支持配置化启停
- 支持有限重试
- 失败时抛出带上下文的业务异常
- 默认关闭时使用 Noop 客户端，不触发外部调用

### 飞书测试接口

新项目文件：

- `src/main/java/com/rcdis/agent/controller/FeishuController.java`
- `src/main/java/com/rcdis/agent/service/FeishuNotificationService.java`
- `src/main/java/com/rcdis/agent/service/impl/FeishuNotificationServiceImpl.java`
- `src/main/java/com/rcdis/agent/dto/FeishuTestMessageRequest.java`
- `src/main/java/com/rcdis/agent/dto/FeishuMessageResponse.java`

接口：

- `POST /api/feishu/test-message`

### 请求上下文和参数注入

新项目文件：

- `src/main/java/com/rcdis/agent/common/context/CurrentUser.java`
- `src/main/java/com/rcdis/agent/common/context/CurrentUserTO.java`
- `src/main/java/com/rcdis/agent/common/context/CurrentUserContextHolder.java`
- `src/main/java/com/rcdis/agent/common/context/CurrentUserArgumentResolver.java`
- `src/main/java/com/rcdis/agent/common/context/RequestContextInterceptor.java`
- `src/main/java/com/rcdis/agent/config/WebMvcConfiguration.java`

当前支持的请求头：

- `X-User-Id`
- `X-User-Name`
- `X-Tenant-Id`
- `X-Conversation-Id`

用途：

- Controller 方法可直接声明 `@CurrentUser CurrentUserTO currentUser`
- Service / MyBatis 审计填充可从 `CurrentUserContextHolder` 获取当前用户
- SSE 异步执行器已携带请求上下文

### MyBatis-Plus 审计字段填充

新项目文件：

- `src/main/java/com/rcdis/agent/entity/BaseEntity.java`
- `src/main/java/com/rcdis/agent/config/MyBatisPlusAuditMetaObjectHandler.java`

当前字段：

- `id`
- `createdAt`
- `updatedAt`
- `createdBy`
- `updatedBy`
- `deleted`
- `deletedAt`
- `deletedBy`

`ResearchProjectEntity` 已继承 `BaseEntity`。

### 审计 AOP

新项目文件：

- `src/main/java/com/rcdis/agent/common/aop/AuditOperation.java`
- `src/main/java/com/rcdis/agent/common/aop/AuditOperationAspect.java`

当前能力：

- 服务方法可标注 `@AuditOperation(action = "...", targetType = "...")`
- 自动记录操作人、租户、耗时、成功或失败状态
- 当前先进入应用日志，后续接审计表

### 通用工具类

新项目文件：

- `src/main/java/com/rcdis/agent/common/util/MoneyUtils.java`
- `src/main/java/com/rcdis/agent/common/util/DateTimeUtils.java`
- `src/main/java/com/rcdis/agent/common/util/DateRange.java`
- `src/main/java/com/rcdis/agent/common/util/CsvUtils.java`
- `src/main/java/com/rcdis/agent/common/util/CsvTable.java`
- `src/main/java/com/rcdis/agent/common/util/HashUtils.java`

对应旧项目：

- `BigDecimalUtils.java`
- `number/DecimalUtil.java`
- `date/DateUtil.java`
- `CsvUtils.java`
- `EncUtil.java`

迁移调整：

- 金额工具改为严格模式，金额为空或格式错误直接抛异常
- 日期工具改用 Java 17 `java.time`，查询范围统一为左闭右开
- CSV 工具保留引号和逗号解析能力，并补充表头重复、列数超限等校验
- 加密散列工具只保留 SHA-256 和 HMAC-SHA256，不迁移 MD5 作为业务默认校验算法

## 暂不迁移的旧模块

### FeishuCommandService

暂不整体迁移。

原因：

- 旧命令与原项目业务强绑定
- 命令解析逻辑不适合直接进入实验室经费管理 Agent
- 后续应结合 Spring AI tool calling 和经费领域服务重新设计

### HttpClientUtil

不迁移。

原因：

- 旧 Apache HttpClient 风格较重
- 存在宽泛异常处理和历史代理配置
- Spring Boot 3 中优先使用 `RestClient` 或 `WebClient`

### EncodingUtils

暂不迁移。

原因：

- 旧实现偏启发式判断，容易对纯 ASCII 或复杂文件做出不确定结论
- 后续如果做票据、CSV、附件导入，应结合文件上传模块明确编码策略

### RestComp 全量方法

不整体迁移。

原因：

- 职责过大
- 混合短信、OCR、支付、内部 RPC、飞书等多个系统
- 当前只抽取飞书通知客户端模式

## 下一步建议

下一步最值得继续迁移的是审计表和飞书回调：

- 建 `audit_log` 表
- 建 `notification_outbox` 表
- 建 `feishu_callback_event` 表
- `@AuditOperation` 写入数据库
- `POST /api/feishu/events` 校验 token、去重、入库，再触发业务处理
