create table if not exists research_project
(
    id                     bigserial primary key,
    project_code           varchar(64) not null unique,
    project_name           varchar(255) not null,
    principal_investigator varchar(128),
    funding_source         varchar(128),
    total_budget           numeric(18, 2) not null default 0,
    used_amount            numeric(18, 2) not null default 0,
    frozen_amount          numeric(18, 2) not null default 0,
    start_date             date,
    end_date               date,
    status                 varchar(32) not null default 'ACTIVE',
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    created_by             varchar(64),
    updated_by             varchar(64),
    deleted                integer not null default 0,
    deleted_at             timestamptz,
    deleted_by             varchar(64),
    delete_reason          varchar(500),
    version                integer not null default 0
);

alter table if exists research_project
    add column if not exists delete_reason varchar(500);

alter table if exists research_project
    add column if not exists version integer not null default 0;

create index if not exists idx_research_project_deleted
    on research_project (deleted);

-- budget_category table removed (single reimbursement model; budget control is project-level).

-- expense_record table removed (spend facts now live on reimbursement_item).

create table if not exists reimbursement_order
(
    id               bigserial primary key,
    reimbursement_no varchar(32) not null unique,
    project_id       bigint not null references research_project (id),
    applicant        varchar(64) not null,
    total_amount     numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'draft',
    payment_type     varchar(32) not null default 'reimbursement',
    submitted_at     timestamptz,
    approved_at      timestamptz,
    reject_reason    varchar(500),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    created_by       varchar(64),
    updated_by       varchar(64),
    deleted          integer not null default 0,
    deleted_at       timestamptz,
    deleted_by       varchar(64),
    delete_reason    varchar(500),
    version          integer not null default 0
);

create index if not exists idx_reimbursement_order_project
    on reimbursement_order (project_id);

create index if not exists idx_reimbursement_order_status
    on reimbursement_order (status);

create index if not exists idx_reimbursement_order_deleted
    on reimbursement_order (deleted);

create sequence if not exists reimbursement_no_seq;

create table if not exists reimbursement_item
(
    id                    bigserial primary key,
    reimbursement_id      bigint not null references reimbursement_order (id),
    amount                numeric(18, 2) not null,
    expense_date          date not null,
    vendor                varchar(128),
    invoice_no            varchar(128),
    receipt_file          varchar(255),
    description           varchar(500) not null,
    counterparty_account  varchar(128),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    created_by            varchar(64),
    updated_by            varchar(64),
    deleted               integer not null default 0,
    deleted_at            timestamptz,
    deleted_by            varchar(64),
    delete_reason         varchar(500)
);

create index if not exists idx_reimbursement_item_order
    on reimbursement_item (reimbursement_id);

create table if not exists audit_log
(
    id              bigserial primary key,
    actor           varchar(64) not null,
    tenant_id       varchar(64) not null,
    action          varchar(128) not null,
    target_type     varchar(128) not null,
    target_id       varchar(128),
    before_snapshot text,
    after_snapshot  text,
    reason          varchar(500),
    source          varchar(64) not null,
    conversation_id varchar(128),
    created_at      timestamptz not null default now()
);

create index if not exists idx_audit_log_target
    on audit_log (target_type, target_id);

create index if not exists idx_audit_log_created_at
    on audit_log (created_at);

create table if not exists notification_outbox
(
    id              bigserial primary key,
    channel         varchar(32) not null,
    target          varchar(255) not null,
    message_type    varchar(32) not null,
    payload         text not null,
    status          varchar(32) not null default 'PENDING',
    idempotency_key varchar(128) not null unique,
    error_message   varchar(1000),
    sent_at         timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index if not exists idx_notification_outbox_status
    on notification_outbox (status);

create index if not exists idx_notification_outbox_created_at
    on notification_outbox (created_at);

create table if not exists feishu_callback_event
(
    id              bigserial primary key,
    event_id        varchar(128) not null unique,
    event_type      varchar(128) not null,
    sender_id       varchar(128),
    chat_id         varchar(128),
    message_id      varchar(128),
    payload         text not null,
    idempotency_key varchar(128) not null unique,
    processed       boolean not null default false,
    processed_at    timestamptz,
    created_at      timestamptz not null default now()
);

create index if not exists idx_feishu_callback_event_processed
    on feishu_callback_event (processed);

-- Approver bindings. Interactive approval cards are private-messaged to these users only, and a
-- card button click is authorized against this table. Without a binding here the callback is
-- rejected, because a Feishu card cannot hide buttons per recipient.
create table if not exists feishu_approver
(
    id            bigserial primary key,
    open_id       varchar(128) not null,
    user_id       varchar(64) not null,
    user_name     varchar(128) not null,
    tenant_id     varchar(64) not null default 'default',
    role          varchar(32) not null default 'APPROVER',
    remark        varchar(500),
    status        varchar(32) not null default 'ACTIVE',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamptz,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create unique index if not exists uk_feishu_approver_open_id_active
    on feishu_approver (open_id)
    where deleted = 0;

create index if not exists idx_feishu_approver_status
    on feishu_approver (status);

create table if not exists notification_template
(
    id            bigserial primary key,
    template_code varchar(64) not null unique,
    template_name varchar(128) not null,
    scene         varchar(64),
    description   varchar(500),
    message_type  varchar(32) not null default 'text',
    content       text not null,
    builtin       integer not null default 0,
    status        varchar(32) not null default 'ACTIVE',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamptz,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create index if not exists idx_notification_template_status
    on notification_template (status);

-- Model provider registry. Replaces the former application.yml-only configuration so that
-- custom models can be added at runtime. api_key_cipher holds an AES-GCM ciphertext, never plaintext.
create table if not exists model_provider
(
    id                    bigserial primary key,
    provider_code         varchar(64) not null,
    provider_name         varchar(128) not null,
    protocol              varchar(32) not null default 'openai-compatible',
    base_url              varchar(512) not null,
    chat_completions_path varchar(255) not null default '/v1/chat/completions',
    models_path           varchar(255) not null default '/v1/models',
    api_key_cipher        varchar(2048),
    api_key_hint          varchar(64),
    timeout_seconds       integer not null default 30,
    temperature           numeric(4, 3),
    max_tokens            integer,
    is_default            integer not null default 0,
    builtin               integer not null default 0,
    status                varchar(32) not null default 'ACTIVE',
    description           varchar(500),
    last_test_status      varchar(32),
    last_test_http_status integer,
    last_test_message     varchar(1000),
    last_test_latency_ms  bigint,
    last_test_at          timestamptz,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    created_by            varchar(64),
    updated_by            varchar(64),
    deleted               integer not null default 0,
    deleted_at            timestamptz,
    deleted_by            varchar(64),
    delete_reason         varchar(500),
    version               integer not null default 0
);

create unique index if not exists uk_model_provider_code_active
    on model_provider (provider_code)
    where deleted = 0;

create index if not exists idx_model_provider_status
    on model_provider (status);

-- Models exposed by a provider. One row per model, at most one default per provider.
create table if not exists model_provider_model
(
    id              bigserial primary key,
    provider_id     bigint not null references model_provider (id),
    model_name      varchar(128) not null,
    display_name    varchar(128),
    temperature     numeric(4, 3),
    max_tokens      integer,
    is_default      integer not null default 0,
    source          varchar(32) not null default 'MANUAL',
    status          varchar(32) not null default 'ACTIVE',
    remark          varchar(500),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamptz,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create unique index if not exists uk_model_provider_model_active
    on model_provider_model (provider_id, model_name)
    where deleted = 0;

create index if not exists idx_model_provider_model_provider
    on model_provider_model (provider_id);

-- ---------- RBAC: users, roles and user-role assignments ----------
-- sys_user holds real login accounts. password_hash stores a BCrypt digest, never a plaintext
-- password. username is unique among non-deleted rows so a soft-deleted account can be reissued.
create table if not exists sys_user
(
    id            bigserial primary key,
    username      varchar(64) not null,
    password_hash varchar(100) not null,
    display_name  varchar(128) not null,
    tenant_id     varchar(64) not null default 'default',
    status        varchar(32) not null default 'ACTIVE',
    last_login_at timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamptz,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create unique index if not exists uk_sys_user_username_active
    on sys_user (username)
    where deleted = 0;

create index if not exists idx_sys_user_status
    on sys_user (status);

-- sys_role holds the three built-in roles: ADMIN, APPROVER and RESEARCHER. code is the stable
-- identifier carried in the JWT roles claim and matched by hasRole(...).
create table if not exists sys_role
(
    id            bigserial primary key,
    code          varchar(32) not null,
    name          varchar(64) not null,
    description   varchar(255),
    status        varchar(32) not null default 'ACTIVE',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamptz,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create unique index if not exists uk_sys_role_code_active
    on sys_role (code)
    where deleted = 0;

-- sys_user_role is a lightweight join table. It carries no soft-delete columns; an assignment is
-- removed by a hard delete when a user's roles are reassigned.
create table if not exists sys_user_role
(
    id         bigserial primary key,
    user_id    bigint not null references sys_user (id),
    role_id    bigint not null references sys_role (id),
    created_at timestamptz not null default now(),
    created_by varchar(64)
);

create unique index if not exists uk_sys_user_role
    on sys_user_role (user_id, role_id);

create index if not exists idx_sys_user_role_user
    on sys_user_role (user_id);

-- ---------- Seed built-in roles and a default admin account ----------
-- A freshly initialized database is immediately usable with admin / admin123. The password_hash below
-- is a Spring BCryptPasswordEncoder digest of "admin123"; change it after the first login. The backend
-- UserSeeder seeds the same rows on startup and skips whatever already exists, so the two stay
-- consistent. Every statement is idempotent (WHERE NOT EXISTS): re-running this script never
-- duplicates rows and never overwrites a password an administrator has since changed.
INSERT INTO sys_role (code, name, description, status)
SELECT 'ADMIN', '系统管理员', '拥有全部权限，含用户管理、模型供应商、飞书配置、项目与预算维护', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'ADMIN' AND deleted = 0);

INSERT INTO sys_role (code, name, description, status)
SELECT 'APPROVER', '带审批的科研人员', '拥有科研人员的全部权限，外加报销审批/驳回与审计日志查看', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'APPROVER' AND deleted = 0);

INSERT INTO sys_role (code, name, description, status)
SELECT 'RESEARCHER', '科研人员', '日常业务操作（支出、报销、凭证、对话）与全量只读', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'RESEARCHER' AND deleted = 0);

INSERT INTO sys_user (username, password_hash, display_name, tenant_id, status)
SELECT 'admin', '$2a$10$OGGhUKjMRu3d9Yx8Ny1pSuZJI1k2Mt3F6Ba6CNRWbrtFYL4GYeFzu', '系统管理员', 'default', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin' AND deleted = 0);

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
         JOIN sys_role r ON r.code = 'ADMIN' AND r.deleted = 0
WHERE u.username = 'admin'
  AND u.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- ---------- Agent chat memory ----------
-- chat_session holds one row per conversation. conversation_id is the client-visible identifier
-- (a UUID generated on the frontend) and is unique among non-deleted rows, so a browser refresh
-- keeps pointing at the same server-side session. Ownership is enforced in the service layer by
-- comparing user_id against the JWT principal.
create table if not exists chat_session
(
    id              bigserial primary key,
    conversation_id varchar(64) not null,
    title           varchar(255),
    user_id         varchar(64) not null,
    tenant_id       varchar(64) not null default 'default',
    provider_code   varchar(64),
    status          varchar(32) not null default 'ACTIVE',
    message_count   integer not null default 0,
    last_message_at timestamptz,
    rolling_summary   text,
    summary_facts     text,
    summary_upto_seq  integer,
    last_compressed_at timestamptz,
    compress_count    integer not null default 0,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamptz,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create unique index if not exists uk_chat_session_conversation_active
    on chat_session (conversation_id)
    where deleted = 0;

create index if not exists idx_chat_session_user
    on chat_session (user_id, deleted);

alter table if exists chat_session add column if not exists rolling_summary text;
alter table if exists chat_session add column if not exists summary_facts text;
alter table if exists chat_session add column if not exists summary_upto_seq integer;
alter table if exists chat_session add column if not exists last_compressed_at timestamptz;
alter table if exists chat_session add column if not exists compress_count integer not null default 0;

-- chat_message stores each turn verbatim, in the shape the model sees it. role follows the OpenAI
-- convention: user / assistant / system / tool. content is nullable so an assistant row can be
-- inserted with status='STREAMING' before the first token arrives, then updated on completion.
create table if not exists chat_message
(
    id              bigserial primary key,
    session_id      bigint not null references chat_session (id),
    conversation_id varchar(64) not null,
    seq             integer not null default 0,
    role            varchar(16) not null,
    content         text,
    provider_code   varchar(64),
    model_name      varchar(128),
    status          varchar(32) not null default 'DONE',
    error_message   varchar(1000),
    token_count     integer,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamptz,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_chat_message_session
    on chat_message (session_id, deleted, id);

create index if not exists idx_chat_message_conversation
    on chat_message (conversation_id, deleted, id);

alter table if exists chat_message add column if not exists seq integer not null default 0;
create index if not exists idx_chat_message_conv_seq
    on chat_message (conversation_id, seq);

-- ---------- Agent pending actions (proposal / commit separation) ----------
-- Write tools never mutate data directly. They insert a PENDING row here describing exactly what
-- would change (before/after snapshots plus the executable arguments), emit a requires_confirmation
-- SSE event, and only when the user approves via POST /api/chat/confirm does the real service call
-- run and the row move to EXECUTED. Rejection moves it to REJECTED. This keeps the LLM out of the
-- write path entirely.
create table if not exists agent_pending_action
(
    id              bigserial primary key,
    conversation_id varchar(64) not null,
    tool_name       varchar(64) not null,
    arguments_json  text not null,
    summary         varchar(1000),
    target_type     varchar(64),
    target_id       varchar(128),
    before_snapshot text,
    after_snapshot  text,
    reason          varchar(500),
    scope           varchar(255),
    status          varchar(32) not null default 'PENDING',
    command_id      varchar(64) not null,
    actor_username  varchar(128),
    actor_roles     varchar(255),
    execution_owner varchar(64),
    lease_until     timestamptz,
    execution_attempts integer not null default 0,
    result_message  varchar(2000),
    error_message   varchar(1000),
    expires_at      timestamptz,
    executed_at     timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamptz,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_agent_pending_action_conversation
    on agent_pending_action (conversation_id, deleted);

create index if not exists idx_agent_pending_action_status
    on agent_pending_action (status, deleted);
create unique index if not exists uk_agent_pending_action_command
    on agent_pending_action (command_id);

-- ---------- Agent Plan-and-Execute tasks ----------
-- A task is a bounded deterministic workflow. Planning is read-only; execution starts only after
-- one pending-action confirmation. Successful steps are never replayed during a retry.
create table if not exists agent_task
(
    id               bigserial primary key,
    conversation_id  varchar(64) not null,
    task_type         varchar(64) not null,
    title             varchar(255) not null,
    status            varchar(32) not null,
    input_json        text not null,
    plan_json         text not null,
    total_steps       integer not null default 0,
    completed_steps   integer not null default 0,
    failed_steps      integer not null default 0,
    error_message     varchar(1000),
    started_at        timestamptz,
    completed_at      timestamptz,
    execution_owner   varchar(64),
    lease_until       timestamptz,
    execution_attempts integer not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    created_by        varchar(64),
    updated_by        varchar(64),
    deleted           integer not null default 0,
    deleted_at        timestamptz,
    deleted_by        varchar(64),
    delete_reason     varchar(500),
    version           integer not null default 0
);

create index if not exists idx_agent_task_owner_status
    on agent_task (created_by, status, deleted);

create table if not exists agent_task_step
(
    id               bigserial primary key,
    task_id          bigint not null references agent_task (id),
    step_no          integer not null,
    action           varchar(64) not null,
    target_type      varchar(64) not null,
    target_id        varchar(128) not null,
    input_json       text not null,
    output_json      text,
    status           varchar(32) not null,
    error_message    varchar(1000),
    started_at       timestamptz,
    completed_at     timestamptz,
    execution_owner  varchar(64),
    lease_until      timestamptz,
    execution_attempts integer not null default 0,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    created_by       varchar(64),
    updated_by       varchar(64),
    deleted          integer not null default 0,
    deleted_at       timestamptz,
    deleted_by       varchar(64),
    delete_reason    varchar(500),
    version          integer not null default 0,
    constraint uk_agent_task_step unique (task_id, step_no)
);

create index if not exists idx_agent_task_step_task
    on agent_task_step (task_id, step_no, deleted);

-- ---------- Agent turn trace (observability) ----------
-- One row per Agent turn: token usage, latency (first-token and total) and the ordered tool-call
-- chain, so the workbench "context trace" tab can replay exactly what a turn did and what it cost.
create table if not exists agent_turn_trace
(
    id               bigserial primary key,
    conversation_id  varchar(64) not null,
    turn_seq         integer not null,
    provider_code    varchar(64),
    model_name       varchar(128),
    status           varchar(32) not null default 'DONE',
    prompt_tokens    integer,
    completion_tokens integer,
    total_tokens     integer,
    first_token_ms   bigint,
    total_ms         bigint,
    tool_calls_json  text,
    error_message    varchar(1000),
    created_at       timestamptz not null default now()
);

create index if not exists idx_agent_turn_trace_conversation
    on agent_turn_trace (conversation_id, id);

-- Binary receipt data stays on filesystem; PostgreSQL stores bounded ownership metadata only.
create table if not exists uploaded_file
(
    id            bigserial primary key,
    owner_user_id varchar(64)  not null,
    category      varchar(32)  not null,
    file_name     varchar(255) not null unique,
    stored_path   varchar(500) not null,
    mime          varchar(128),
    size_bytes    bigint,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz,
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       smallint not null default 0,
    deleted_at    timestamptz,
    deleted_by    varchar(64),
    delete_reason varchar(500)
);

-- ---------- Agent attachments (uploaded documents for context) ----------
-- Files uploaded into a conversation. Text-based formats (txt/pdf/docx/pptx) have their text
-- extracted at upload time and stored in extracted_text so the Agent can reason over them without
-- re-parsing. Images are stored but not OCR'd yet (extract_status=UNSUPPORTED).
create table if not exists agent_attachment
(
    id             bigserial primary key,
    conversation_id varchar(64),
    original_name  varchar(255) not null,
    stored_path    varchar(500) not null,
    url            varchar(500) not null,
    mime           varchar(128),
    ext            varchar(16),
    size_bytes     bigint,
    kind           varchar(16) not null default 'TEXT',
    extracted_text text,
    extract_status varchar(16) not null default 'OK',
    created_by     varchar(64),
    created_at     timestamptz not null default now()
);

create index if not exists idx_agent_attachment_conversation
    on agent_attachment (conversation_id, id);

-- ---------- Agent cross-session semantic memory ----------
-- Long-term facts about a user (preferences / identity / constraints / decisions) extracted
-- asynchronously from conversations and injected into future sessions. Extractive snippets only.
create table if not exists agent_memory
(
    id                     bigserial primary key,
    scope                  varchar(16)  not null default 'USER',
    owner_user_id          varchar(64),
    project_id             bigint,
    fact_type              varchar(32),
    content                text         not null,
    source_conversation_id varchar(64),
    source_seq             integer,
    hit_count              integer      not null default 0,
    last_hit_at            timestamptz,
    created_by             varchar(64),
    created_at             timestamptz  not null default now()
);

create index if not exists idx_agent_memory_owner
    on agent_memory (owner_user_id, id);
create index if not exists idx_agent_memory_scope_project
    on agent_memory (scope, project_id, id);

-- ---------- Per-user semantic-memory switches (read/write separated) ----------
create table if not exists agent_user_memory_setting
(
    id              bigserial primary key,
    user_id         varchar(64) not null,
    extract_enabled boolean     not null default true,
    inject_enabled  boolean     not null default true,
    updated_at      timestamptz not null default now(),
    constraint uk_agent_user_memory_setting unique (user_id)
);

-- ---------- Receipt OCR (vision-model structured extraction, async) ----------
-- One row per uploaded receipt image; raw model output kept for auditability.
create table if not exists receipt_ocr
(
    id            bigserial primary key,
    file_name     varchar(128) not null,
    status        varchar(16)  not null,
    doc_type      varchar(16),
    fields_json   text,
    confidence    numeric(4,3),
    raw_response  text,
    provider_code varchar(64),
    model_name    varchar(128),
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint uk_receipt_ocr_file_name unique (file_name)
);
