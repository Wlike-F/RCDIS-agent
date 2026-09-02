# RCDIS-agent AGENTS.md

## Project Overview

RCDIS-agent is a small-scale laboratory research fund management Agent.

The project focuses on structured financial workflows for laboratory scenarios:

- Research project budget management
- Expense registration and correction
- Reimbursement material checking
- Invoice and attachment metadata management
- Budget balance query and warning
- Approval confirmation and audit trace

The system must behave as a business application with Agent capabilities, not as a free-form chatbot.

## Technology Stack

- Java 17
- Spring Boot 3.x
- Spring AI
- MyBatis-Plus
- PostgreSQL
- SSE for streaming responses
- Lombok
- Feishu bot integration

PostgreSQL is the primary business database.
Do not use ChromaDB as the primary database.
If vector retrieval is needed later, prefer PostgreSQL with pgvector first.

## Architecture Principles

Keep the architecture small, explicit, and business-first.

Recommended module boundaries:

```text
controller
  HTTP APIs, SSE endpoints, request/response DTOs

application
  Use cases, transaction boundaries, orchestration

agent
  Spring AI ChatClient configuration, model provider routing, tool registration, prompt policy

domain
  Core business models and business rules

persistence
  MyBatis-Plus mapper, entity, SQL, repository adapters

infrastructure
  external services, file storage, OCR, model provider clients, Feishu bot clients
```

Do not put business rules inside controllers.
Do not let prompts replace deterministic business logic.
Do not let the Agent directly build unsafe SQL.
Do not hard-code model provider, model name, base URL, or API key in Java code.

## MVC Package Convention

Use a clear MVC-oriented package structure for business modules.

Preferred package layout:

```text
com.rcdis.agent
  ├── controller
  │   └── XxxController
  ├── service
  │   ├── XxxService
  │   └── impl
  │       └── XxxServiceImpl
  ├── mapper
  │   └── XxxMapper
  ├── entity
  │   └── XxxEntity
  ├── vo
  │   └── XxxVO
  ├── to
  │   └── XxxTO
  ├── dto
  │   └── XxxRequest / XxxResponse
  ├── config
  ├── common
  ├── agent
  └── infrastructure
```

Package responsibilities:

- `controller`: REST controllers and SSE endpoints only.
- `service`: business use case interfaces.
- `service.impl`: business use case implementations and transaction boundaries.
- `mapper`: MyBatis-Plus mapper interfaces and custom SQL mapper boundaries.
- `entity`: database persistence objects mapped to PostgreSQL tables.
- `vo`: view objects returned to frontend pages.
- `to`: transfer objects used between internal layers or external integrations.
- `dto`: request and response DTOs for API boundaries.
- `config`: Spring configuration, typed configuration properties, bean wiring.
- `common`: shared result models, exceptions, constants, validation helpers.
- `agent`: Spring AI ChatClient, prompts, tool registration, model provider routing.
- `infrastructure`: Feishu, file storage, OCR, external APIs, model provider clients.

Naming rules:

- Controllers end with `Controller`.
- Services end with `Service`.
- Service implementations end with `ServiceImpl`.
- MyBatis-Plus mappers end with `Mapper`.
- Database objects end with `Entity`.
- Frontend response view objects end with `VO`.
- Internal transfer objects end with `TO`.
- API request objects end with `Request`.
- API response objects end with `Response`.

Rules:

- Do not return `Entity` objects directly from controllers.
- Controllers should receive `Request` objects and return `Response` or `VO` objects.
- Services should not depend on controller classes.
- Mappers should not be called directly from controllers.
- Agent tools should call services, not mappers.
- Infrastructure clients should return `TO` objects or dedicated response records, not database entities.
- Keep package names consistent after the first scaffold is created; use `controller` for REST APIs and SSE endpoints.

## Agent Design Rules

Use Spring AI as the Agent integration layer.
Do not build a custom Agent loop unless there is a clear reason.

The Agent should call typed tools backed by domain services:

- `query_project_budget`
- `record_expense`
- `update_expense`
- `check_reimbursement_materials`
- `generate_reimbursement_summary`
- `list_audit_logs`

The model is not the source of truth.
All balances, expenses, project information, reimbursement status, and audit records must come from PostgreSQL or verified files.

The Agent must not invent:

- Budget balances
- Project numbers
- Expense records
- Invoice information
- Approval status
- Policy requirements

High-risk operations require explicit confirmation:

- Creating financial records
- Updating amount, date, project, category, or reimbursement status
- Deleting or voiding records
- Submitting reimbursement
- Changing budget allocations

Every high-risk operation must produce an audit log.

## Model Provider Configuration

Model provider management is a first-class product capability.

The system should support multiple model providers, such as:

- OpenAI-compatible providers
- DeepSeek
- Qwen
- OpenAI
- Ollama or other local models

Early-stage configuration may live in `application.yml`.
The configuration must be structured so it can later migrate to PostgreSQL without changing Agent business logic.

Example configuration shape:

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

Rules:

- Read model provider config through typed Spring `@ConfigurationProperties`.
- Keep API keys in environment variables or external secret configuration.
- Do not expose raw API keys to the frontend.
- Frontend pages may display provider name, type, base URL, model name, status, and masked key status only.
- Every Agent request must resolve the active provider through a model provider registry or router.
- Support one default provider and optional per-session provider selection.
- Log provider name and model name for observability, but never log API keys.

The frontend should include a model provider settings page.

Minimum UI capabilities:

- List configured providers
- Add provider
- Edit provider metadata
- Enable or disable provider
- Set default provider
- Test connectivity
- Choose active model for a session

For the first version, frontend writes may update in-memory state or a local configuration abstraction.
Do not edit `application.yml` directly from the frontend unless the project explicitly implements a safe configuration persistence mechanism.

When the project moves beyond the prototype stage, store provider metadata in PostgreSQL and store secrets in environment variables, a secrets manager, or encrypted storage.

## Database Rules

Use PostgreSQL as the primary database.
Use MyBatis-Plus for standard CRUD.
Use explicit mapper SQL for complex reports and financial summaries.

Financial data rules:

- Use `BigDecimal` for money.
- Use database transactions for multi-step financial changes.
- Use soft delete for business records unless hard delete is explicitly required.
- Keep audit tables for create, update, delete, and approval actions.
- Store timestamps with clear timezone handling.
- Use optimistic locking where concurrent edits are possible.

SQL rules:

- Never concatenate user input into SQL.
- Use mapper parameters, wrappers, or prepared statements.
- Query methods must make limit behavior explicit.
- Do not silently default to small limits for financial records.
- Pagination must be explicit in API request DTOs.

## SSE Rules

Use SSE for chat streaming and long-running Agent workflows.

SSE events should be explicit:

- `start`
- `token`
- `tool_start`
- `tool_result`
- `requires_confirmation`
- `error`
- `done`

Do not stream partial financial decisions as final facts.
When a response depends on a tool result, stream the reasoning text only after the verified data is available.

## Coding Style

- Use Java 17 language features conservatively.
- Prefer simple functions and small services.
- Keep classes single-purpose.
- Use clear DTOs for API boundaries.
- Use explicit return types.
- Avoid global mutable state.
- Avoid catch-all exception handlers that hide root causes.
- Error messages must include enough context for debugging.
- Code comments must be in English.
- Keep business terminology consistent.
- Use Lombok only where it reduces boilerplate without hiding business logic.
- Prefer `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`, and explicit constructors where appropriate.
- Do not use Lombok `@Data` on domain objects when equality, mutability, or API exposure needs to be controlled.

## Logging Rules

Use Lombok `@Slf4j` with SLF4J for all application logs.

Do not use:

- `System.out.println`
- `System.err.println`
- `printStackTrace`

Logging rules:

- Use `log.atInfo()`, `log.atWarn()`, and `log.atError()` when structured fields are needed.
- Include stable business identifiers such as `projectId`, `expenseId`, `reimbursementId`, and `conversationId`.
- Do not log secrets, API keys, Feishu signatures, access tokens, invoice images, personal phone numbers, or bank account data.
- Log external API failures with status code, request id, endpoint name, and sanitized response body.
- Log high-risk operations and confirmation decisions through both application logs and audit tables.
- Do not use logs as the only audit source for financial operations.

Example:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseApplicationService {

    public void recordExpense(Long projectId, Long expenseId) {
        log.atInfo()
                .addKeyValue("projectId", projectId)
                .addKeyValue("expenseId", expenseId)
                .log("Expense recorded");
    }
}
```

## Feishu Bot Integration

Feishu bot integration is part of the infrastructure layer.

Use Feishu bot for:

- Budget warning notifications
- Reimbursement material missing-field reminders
- Approval confirmation messages
- Agent workflow status updates
- Daily or weekly funding summaries

Do not use Feishu bot as the source of truth.
Feishu messages are notifications or interaction channels only.
All financial state must be stored in PostgreSQL.

Integration rules:

- Keep Feishu webhook URLs, app secrets, verification tokens, and signing secrets outside source code.
- Read Feishu configuration from environment variables or externalized Spring configuration.
- Verify inbound Feishu callbacks before processing.
- Use idempotency keys for callback and approval actions.
- Retry transient Feishu API failures with bounded retries, then raise the last error.
- Store outbound notification records and inbound callback records for auditability.
- Never send sensitive full invoice images, bank account data, or private identity information to group chats by default.
- For approval-like interactions, require an explicit user action and write an audit record before changing financial state.

## Spring Rules

- Controllers only handle transport concerns.
- Services handle business use cases.
- Use constructor injection.
- Keep configuration in `application.yml`.
- Keep secrets out of source code.
- Use profiles for local, test, and production-like configs.
- Prefer validation annotations on request DTOs.

## Testing Rules

Follow a practical testing strategy:

- Prefer integration tests for business workflows.
- Prefer real database tests with Testcontainers or a local PostgreSQL-compatible setup.
- Add unit tests only for stable pure functions or complex deterministic transformations.
- Test high-risk financial operations through service-level integration tests.
- Test SSE endpoints with smoke tests.

Minimum workflow tests:

- Create expense
- Query project budget
- Update expense with audit log
- Soft delete expense with reason
- Reimbursement material check
- Confirmation-required operation

## Development Workflow

- Inspect existing code before editing.
- Keep changes focused on the current task.
- Do not introduce a framework unless it removes real complexity.
- Do not create commits unless explicitly requested.
- Run relevant tests after code changes.
- Prefer current project conventions over personal style.

## Product Constraints

This project should optimize for correctness, traceability, and explainability.

For laboratory fund management, a useful answer is:

- Based on real data
- Traceable to records or files
- Clear about uncertainty
- Conservative for write operations
- Easy to audit later

Do not optimize for playful personality, vague conversation, or impressive but unverifiable answers.
