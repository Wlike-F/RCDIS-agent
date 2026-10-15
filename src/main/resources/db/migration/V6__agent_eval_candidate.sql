-- Candidates for the Agent evaluation set, discovered from real failing turns.
--
-- A candidate is not yet a test case: it records what actually went wrong online plus the evidence.
-- Promotion into src/test/resources/agent-eval/cases.jsonl requires a person to confirm the expected
-- behaviour (expected_behavior / confirmed_by / confirmed_at) and to record an isolated-environment
-- reproduction (reproduce_env / reproduced_at / reproduce_run_id). Nothing writes into the dataset
-- automatically.
create table if not exists agent_eval_candidate
(
    id                     bigserial primary key,
    -- sha256 of failure type + redacted input + observed tool set; the dedup key.
    fingerprint            varchar(64)  not null,
    source                 varchar(32)  not null,
    failure_type           varchar(32)  not null,
    category_guess         varchar(32),
    risk_level             varchar(16)  not null default 'MEDIUM',
    input_text             varchar(2000),
    observed_tools_json    varchar(2000),
    evidence_json          varchar(4000),
    source_conversation_id varchar(64),
    source_trace_ids       varchar(500),
    occurrences            integer      not null default 1,
    first_seen_at          timestamptz  not null default now(),
    last_seen_at           timestamptz  not null default now(),
    -- PENDING_REVIEW -> APPROVED -> (reproduced) -> PROMOTED, or REJECTED.
    status                 varchar(24)  not null default 'PENDING_REVIEW',
    expected_behavior      varchar(2000),
    confirmed_by           varchar(64),
    confirmed_at           timestamptz,
    reproduce_env          varchar(128),
    reproduced_at          timestamptz,
    reproduce_run_id       varchar(64),
    promote_note           varchar(500),
    promoted_case_id       varchar(64),
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz,
    created_by             varchar(64),
    updated_by             varchar(64),
    deleted                integer      not null default 0,
    deleted_at             timestamptz,
    deleted_by             varchar(64),
    delete_reason          varchar(500),
    constraint uk_agent_eval_candidate_fingerprint unique (fingerprint)
);

-- Triage sorts by how often a failure actually happened, then by recency.
create index if not exists idx_agent_eval_candidate_status
    on agent_eval_candidate (status, occurrences desc);
