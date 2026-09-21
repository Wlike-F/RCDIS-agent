-- Upgrade databases whose Agent tables predate command idempotency and execution leases.
-- Columns are added nullable first so rows already in production remain migratable.

alter table if exists agent_pending_action
    add column if not exists command_id varchar(64);
alter table if exists agent_pending_action
    add column if not exists actor_username varchar(128);
alter table if exists agent_pending_action
    add column if not exists actor_roles varchar(255);
alter table if exists agent_pending_action
    add column if not exists execution_owner varchar(64);
alter table if exists agent_pending_action
    add column if not exists lease_until timestamptz;
alter table if exists agent_pending_action
    add column if not exists execution_attempts integer not null default 0;
alter table if exists agent_pending_action
    add column if not exists result_message varchar(2000);

update agent_pending_action
set command_id = 'legacy-pending-' || id
where command_id is null or btrim(command_id) = '';

alter table if exists agent_pending_action
    alter column command_id set not null;

create unique index if not exists uk_agent_pending_action_command
    on agent_pending_action (command_id);

alter table if exists agent_task
    add column if not exists execution_owner varchar(64);
alter table if exists agent_task
    add column if not exists lease_until timestamptz;
alter table if exists agent_task
    add column if not exists execution_attempts integer not null default 0;

alter table if exists agent_task_step
    add column if not exists execution_owner varchar(64);
alter table if exists agent_task_step
    add column if not exists lease_until timestamptz;
alter table if exists agent_task_step
    add column if not exists execution_attempts integer not null default 0;

create index if not exists idx_agent_pending_action_recovery
    on agent_pending_action (status, lease_until, deleted);
create index if not exists idx_agent_task_recovery
    on agent_task (status, lease_until, deleted);
create index if not exists idx_agent_task_step_recovery
    on agent_task_step (status, lease_until, deleted);
