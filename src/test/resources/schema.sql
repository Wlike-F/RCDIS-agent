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

create table if not exists budget_category
(
    id               bigserial primary key,
    project_id       bigint not null,
    category_code    varchar(64) not null,
    category_name    varchar(128) not null,
    allocated_amount numeric(18, 2) not null default 0,
    used_amount      numeric(18, 2) not null default 0,
    frozen_amount    numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'ACTIVE',
    remark           varchar(500),
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

create table if not exists expense_record
(
    id                 bigserial primary key,
    project_id         bigint not null,
    budget_category_id bigint not null,
    amount             numeric(18, 2) not null,
    expense_date       date not null,
    vendor             varchar(128),
    invoice_no         varchar(128),
    receipt_file       varchar(255),
    description        varchar(500) not null,
    status             varchar(32) not null default 'REGISTERED',
    created_at         timestamp with time zone not null default now(),
    updated_at         timestamp with time zone not null default now(),
    created_by         varchar(64),
    updated_by         varchar(64),
    deleted            integer not null default 0,
    deleted_at         timestamp with time zone,
    deleted_by         varchar(64),
    delete_reason      varchar(500),
    version            integer not null default 0
);

create table if not exists reimbursement_order
(
    id               bigserial primary key,
    reimbursement_no varchar(32) not null unique,
    project_id       bigint not null,
    applicant        varchar(64) not null,
    total_amount     numeric(18, 2) not null default 0,
    status           varchar(32) not null default 'DRAFT',
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
    id                bigserial primary key,
    reimbursement_id  bigint not null,
    expense_id        bigint not null unique,
    amount            numeric(18, 2) not null,
    created_at        timestamp with time zone not null default now(),
    updated_at        timestamp with time zone not null default now(),
    created_by        varchar(64),
    updated_by        varchar(64),
    deleted           integer not null default 0,
    deleted_at        timestamp with time zone,
    deleted_by        varchar(64),
    delete_reason     varchar(500)
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
