-- Runtime self-healing (circuit breaker) observations for one Agent turn.
-- Nullable columns so existing rows remain valid; halt data only populates for turns where the
-- breaker fired. See com.rcdis.agent.agent.RecordingToolCallback for the write path.

alter table if exists agent_turn_trace
    add column if not exists halt_reason varchar(32);
alter table if exists agent_turn_trace
    add column if not exists halt_count integer not null default 0;
alter table if exists agent_turn_trace
    add column if not exists frozen_tools_json text;

create index if not exists idx_agent_turn_trace_halt
    on agent_turn_trace (halt_reason, created_at desc);
