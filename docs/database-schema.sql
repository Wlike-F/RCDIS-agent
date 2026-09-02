create table if not exists research_project
(
    id                     bigserial primary key,
    project_code           varchar(64) not null unique,
    project_name           varchar(255) not null,
    principal_investigator varchar(128),
    funding_source         varchar(128),
    total_budget           numeric(18, 2) not null default 0,
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

create table if not exists budget_category
(
    id               bigserial primary key,
    project_id       bigint not null references research_project (id),
    category_code    varchar(64) not null,
    category_name    varchar(128) not null,
    allocated_amount numeric(18, 2) not null default 0,
    used_amount      numeric(18, 2) not null default 0,
    frozen_amount    numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'ACTIVE',
    remark           varchar(500),
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

create unique index if not exists uk_budget_category_project_code_active
    on budget_category (project_id, category_code)
    where deleted = 0;

create index if not exists idx_budget_category_project
    on budget_category (project_id);

create table if not exists expense_record
(
    id                 bigserial primary key,
    project_id         bigint not null references research_project (id),
    budget_category_id bigint not null references budget_category (id),
    amount             numeric(18, 2) not null,
    expense_date       date not null,
    vendor             varchar(128),
    invoice_no         varchar(128),
    receipt_file       varchar(255),
    description        varchar(500) not null,
    status             varchar(32) not null default 'REGISTERED',
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    created_by         varchar(64),
    updated_by         varchar(64),
    deleted            integer not null default 0,
    deleted_at         timestamptz,
    deleted_by         varchar(64),
    delete_reason      varchar(500),
    version            integer not null default 0
);

create index if not exists idx_expense_record_project
    on expense_record (project_id);

create index if not exists idx_expense_record_budget_category
    on expense_record (budget_category_id);

create index if not exists idx_expense_record_expense_date
    on expense_record (expense_date);

create index if not exists idx_expense_record_status
    on expense_record (status);

create index if not exists idx_expense_record_deleted
    on expense_record (deleted);

alter table if exists expense_record
    add column if not exists receipt_file varchar(255);

create table if not exists reimbursement_order
(
    id               bigserial primary key,
    reimbursement_no varchar(32) not null unique,
    project_id       bigint not null references research_project (id),
    applicant        varchar(64) not null,
    total_amount     numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'DRAFT',
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
    id                bigserial primary key,
    reimbursement_id  bigint not null references reimbursement_order (id),
    expense_id        bigint not null unique references expense_record (id),
    amount            numeric(18, 2) not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    created_by        varchar(64),
    updated_by        varchar(64),
    deleted           integer not null default 0,
    deleted_at        timestamptz,
    deleted_by        varchar(64),
    delete_reason     varchar(500)
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
