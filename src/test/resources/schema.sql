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
    created_at             timestamp with time zone not null default now(),
    updated_at             timestamp with time zone not null default now(),
    created_by             varchar(64),
    updated_by             varchar(64),
    deleted                integer not null default 0,
    deleted_at             timestamp with time zone,
    deleted_by             varchar(64),
    delete_reason          varchar(500),
    version                integer not null default 0
);

-- budget_category and expense_record tables removed (single reimbursement model).

create table if not exists reimbursement_order
(
    id               bigserial primary key,
    reimbursement_no varchar(32) not null unique,
    project_id       bigint not null,
    applicant        varchar(64) not null,
    total_amount     numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'draft',
    payment_type     varchar(32) not null default 'reimbursement',
    submitted_at     timestamp with time zone,
    approved_at      timestamp with time zone,
    reject_reason    varchar(500),
    created_at       timestamp with time zone not null default now(),
    updated_at       timestamp with time zone not null default now(),
    created_by       varchar(64),
    updated_by       varchar(64),
    deleted          integer not null default 0,
    deleted_at       timestamp with time zone,
    deleted_by       varchar(64),
    delete_reason    varchar(500),
    version          integer not null default 0
);

create sequence if not exists reimbursement_no_seq;

create table if not exists reimbursement_item
(
    id                    bigserial primary key,
    reimbursement_id      bigint not null,
    amount                numeric(18, 2) not null,
    expense_date          date not null,
    vendor                varchar(128),
    invoice_no            varchar(128),
    receipt_file          varchar(255),
    description           varchar(500) not null,
    counterparty_account  varchar(128),
    created_at            timestamp with time zone not null default now(),
    updated_at            timestamp with time zone not null default now(),
    created_by            varchar(64),
    updated_by            varchar(64),
    deleted               integer not null default 0,
    deleted_at            timestamp with time zone,
    deleted_by            varchar(64),
    delete_reason         varchar(500)
);

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
    created_at      timestamp with time zone not null default now()
);

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
    sent_at         timestamp with time zone,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now()
);

create index if not exists idx_notification_outbox_status
    on notification_outbox (status);

create index if not exists idx_notification_outbox_created_at
    on notification_outbox (created_at);

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
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamp with time zone,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create index if not exists idx_notification_template_status
    on notification_template (status);

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
    processed_at    timestamp with time zone,
    created_at      timestamp with time zone not null default now()
);

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
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamp with time zone,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create index if not exists idx_feishu_approver_open_id
    on feishu_approver (open_id);

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
    last_test_at          timestamp with time zone,
    created_at            timestamp with time zone not null default now(),
    updated_at            timestamp with time zone not null default now(),
    created_by            varchar(64),
    updated_by            varchar(64),
    deleted               integer not null default 0,
    deleted_at            timestamp with time zone,
    deleted_by            varchar(64),
    delete_reason         varchar(500),
    version               integer not null default 0
);

create index if not exists idx_model_provider_code
    on model_provider (provider_code);

create table if not exists model_provider_model
(
    id              bigserial primary key,
    provider_id     bigint not null,
    model_name      varchar(128) not null,
    display_name    varchar(128),
    temperature     numeric(4, 3),
    max_tokens      integer,
    is_default      integer not null default 0,
    source          varchar(32) not null default 'MANUAL',
    status          varchar(32) not null default 'ACTIVE',
    remark          varchar(500),
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamp with time zone,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_model_provider_model_provider
    on model_provider_model (provider_id);

-- RBAC: users, roles and user-role assignments. H2 does not support partial unique indexes, so
-- username/code uniqueness among non-deleted rows is enforced in the service layer here.
create table if not exists sys_user
(
    id            bigserial primary key,
    username      varchar(64) not null,
    password_hash varchar(100) not null,
    display_name  varchar(128) not null,
    tenant_id     varchar(64) not null default 'default',
    status        varchar(32) not null default 'ACTIVE',
    last_login_at timestamp with time zone,
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamp with time zone,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create index if not exists idx_sys_user_username
    on sys_user (username);

create table if not exists sys_role
(
    id            bigserial primary key,
    code          varchar(32) not null,
    name          varchar(64) not null,
    description   varchar(255),
    status        varchar(32) not null default 'ACTIVE',
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now(),
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamp with time zone,
    deleted_by    varchar(64),
    delete_reason varchar(500),
    version       integer not null default 0
);

create index if not exists idx_sys_role_code
    on sys_role (code);

create table if not exists sys_user_role
(
    id         bigserial primary key,
    user_id    bigint not null,
    role_id    bigint not null,
    created_at timestamp with time zone not null default now(),
    created_by varchar(64)
);

create unique index if not exists uk_sys_user_role
    on sys_user_role (user_id, role_id);

create index if not exists idx_sys_user_role_user
    on sys_user_role (user_id);

-- Agent chat memory. H2 does not support partial unique indexes, so conversation_id uniqueness
-- among non-deleted rows is enforced in the service layer here, matching the sys_user pattern.
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
    last_message_at timestamp with time zone,
    rolling_summary    text,
    summary_facts      text,
    summary_upto_seq   integer,
    last_compressed_at timestamp with time zone,
    compress_count     integer not null default 0,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamp with time zone,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_chat_session_conversation
    on chat_session (conversation_id);

create table if not exists chat_message
(
    id              bigserial primary key,
    session_id      bigint not null,
    conversation_id   varchar(64) not null,
    seq               integer not null default 0,
    role              varchar(16) not null,
    content           text,
    provider_code   varchar(64),
    model_name      varchar(128),
    status          varchar(32) not null default 'DONE',
    error_message   varchar(1000),
    token_count     integer,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamp with time zone,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_chat_message_session
    on chat_message (session_id, id);

create index if not exists idx_chat_message_conversation
    on chat_message (conversation_id, id);

-- Agent pending actions (proposal / commit separation). H2-compatible: no partial indexes.
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
    lease_until     timestamp with time zone,
    execution_attempts integer not null default 0,
    result_message  varchar(2000),
    error_message   varchar(1000),
    expires_at      timestamp with time zone,
    executed_at     timestamp with time zone,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now(),
    created_by      varchar(64),
    updated_by      varchar(64),
    deleted         integer not null default 0,
    deleted_at      timestamp with time zone,
    deleted_by      varchar(64),
    delete_reason   varchar(500),
    version         integer not null default 0
);

create index if not exists idx_agent_pending_action_conversation
    on agent_pending_action (conversation_id, id);

create index if not exists idx_agent_pending_action_status
    on agent_pending_action (status);
create unique index if not exists uk_agent_pending_action_command
    on agent_pending_action (command_id);

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
    started_at        timestamp with time zone,
    completed_at      timestamp with time zone,
    execution_owner   varchar(64),
    lease_until       timestamp with time zone,
    execution_attempts integer not null default 0,
    created_at        timestamp with time zone not null default now(),
    updated_at        timestamp with time zone not null default now(),
    created_by        varchar(64),
    updated_by        varchar(64),
    deleted           integer not null default 0,
    deleted_at        timestamp with time zone,
    deleted_by        varchar(64),
    delete_reason     varchar(500),
    version           integer not null default 0
);

create index if not exists idx_agent_task_owner_status
    on agent_task (created_by, status);

create table if not exists agent_task_step
(
    id               bigserial primary key,
    task_id          bigint not null,
    step_no          integer not null,
    action           varchar(64) not null,
    target_type      varchar(64) not null,
    target_id        varchar(128) not null,
    input_json       text not null,
    output_json      text,
    status           varchar(32) not null,
    error_message    varchar(1000),
    started_at       timestamp with time zone,
    completed_at     timestamp with time zone,
    execution_owner  varchar(64),
    lease_until      timestamp with time zone,
    execution_attempts integer not null default 0,
    created_at       timestamp with time zone not null default now(),
    updated_at       timestamp with time zone not null default now(),
    created_by       varchar(64),
    updated_by       varchar(64),
    deleted           integer not null default 0,
    deleted_at        timestamp with time zone,
    deleted_by        varchar(64),
    delete_reason     varchar(500),
    version           integer not null default 0,
    constraint uk_agent_task_step unique (task_id, step_no)
);

create index if not exists idx_agent_task_step_task
    on agent_task_step (task_id, step_no);

-- Agent turn trace (observability). Append-only, no soft delete.
create table if not exists agent_turn_trace
(
    id                bigserial primary key,
    conversation_id   varchar(64) not null,
    turn_seq          integer not null,
    provider_code     varchar(64),
    model_name        varchar(128),
    status            varchar(32) not null default 'DONE',
    prompt_tokens     integer,
    completion_tokens integer,
    total_tokens      integer,
    first_token_ms    bigint,
    total_ms          bigint,
    tool_calls_json   text,
    error_message     varchar(1000),
    created_at        timestamp with time zone not null default now()
);

create index if not exists idx_agent_turn_trace_conversation
    on agent_turn_trace (conversation_id, id);

create table if not exists uploaded_file
(
    id            bigserial primary key,
    owner_user_id varchar(64)  not null,
    category      varchar(32)  not null,
    file_name     varchar(255) not null unique,
    stored_path   varchar(500) not null,
    mime          varchar(128),
    size_bytes    bigint,
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone,
    created_by    varchar(64),
    updated_by    varchar(64),
    deleted       integer not null default 0,
    deleted_at    timestamp with time zone,
    deleted_by    varchar(64),
    delete_reason varchar(500)
);

-- Agent attachments (uploaded documents for context).
create table if not exists agent_attachment
(
    id              bigserial primary key,
    conversation_id varchar(64),
    original_name   varchar(255) not null,
    stored_path     varchar(500) not null,
    url             varchar(500) not null,
    mime            varchar(128),
    ext             varchar(16),
    size_bytes      bigint,
    kind            varchar(16) not null default 'TEXT',
    extracted_text  text,
    extract_status  varchar(16) not null default 'OK',
    created_by      varchar(64),
    created_at      timestamp with time zone not null default now()
);

create index if not exists idx_agent_attachment_conversation
    on agent_attachment (conversation_id, id);

create table if not exists agent_memory
(
    id                     bigserial primary key,
    scope                  varchar(16) not null default 'USER',
    owner_user_id          varchar(64),
    project_id             bigint,
    fact_type              varchar(32),
    content                text        not null,
    source_conversation_id varchar(64),
    source_seq             integer,
    hit_count              integer     not null default 0,
    last_hit_at            timestamp with time zone,
    created_by             varchar(64),
    created_at             timestamp with time zone not null default now()
);

create index if not exists idx_agent_memory_owner
    on agent_memory (owner_user_id, id);

create table if not exists agent_user_memory_setting
(
    id              bigserial primary key,
    user_id         varchar(64) not null,
    extract_enabled boolean     not null default true,
    inject_enabled  boolean     not null default true,
    updated_at      timestamp with time zone not null default now(),
    constraint uk_agent_user_memory_setting unique (user_id)
);

create table if not exists receipt_ocr
(
    id            bigserial primary key,
    file_name     varchar(128) not null,
    status        varchar(16)  not null,
    doc_type      varchar(16),
    fields_json   text,
    confidence    numeric(6,3),
    raw_response  text,
    provider_code varchar(64),
    model_name    varchar(128),
    error_message text,
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now(),
    constraint uk_receipt_ocr_file_name unique (file_name)
);
