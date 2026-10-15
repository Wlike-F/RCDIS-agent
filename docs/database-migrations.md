# Database migrations

Flyway is the authoritative production schema manager. Application startup runs migrations from
`src/main/resources/db/migration` before MyBatis services are created.

## Existing installations

Existing databases created from `docs/database-schema.sql` are baselined at version `0`. Migration
`V1` is intentionally idempotent, so it validates/adds the current schema without deleting business
data. Flyway then applies all later migrations exactly once. Back up PostgreSQL before the first
Flyway-managed startup and do not manually edit `flyway_schema_history`.

## Legacy receipt migration

`V2` converts uniquely-owned `/uploads/receipts/*` references to the authenticated
`/api/files/receipts/{fileName}/content` endpoint and creates matching `uploaded_file` metadata.
Receipt bytes remain on disk and are never copied into PostgreSQL.

References with malformed names or conflicting applicants are not made accessible. They are recorded
in `legacy_file_migration_issue`; conflicting files are additionally marked as
`RECEIPT_QUARANTINED` with owner `__UNRESOLVED__`. An administrator must inspect the original record,
assign a real owner, confirm that the file exists under the configured receipt directory, update the
metadata category to `RECEIPT`, rewrite the reimbursement item URL, and mark the issue resolved.

Useful acceptance queries:

```sql
select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;

select reason, count(*)
from legacy_file_migration_issue
where resolved = false
group by reason;

select category, count(*)
from uploaded_file
group by category;
```

H2 integration tests keep using `src/test/resources/schema.sql`; Flyway is disabled only in the test
profile because production migrations intentionally use PostgreSQL semantics.

## Agent eval candidates (V6)

`V6__agent_eval_candidate.sql` creates `agent_eval_candidate`: real failing turns harvested from
`agent_turn_trace` and `agent_pending_action`, held as review candidates rather than test cases.

A row only becomes a case in `src/test/resources/agent-eval/cases.jsonl` after a person records the
expected behaviour (`expected_behavior`, `confirmed_by`, `confirmed_at`) and an isolated-environment
reproduction (`reproduce_env`, `reproduced_at`, `reproduce_run_id`). Nothing is inserted into the
dataset by the application; see `docs/agent-eval.md`.

Because `src/test/resources/schema.sql` is maintained by hand while tests run on H2, it drifts from the
migration chain silently — the turn-trace halt columns (`halt_reason`, `halt_count`,
`frozen_tools_json`, added in V5) existed in live databases and in the entity but were missing from
both `src/test/resources/schema.sql` and `docs/database-schema.sql`. When adding a migration, update
those two files in the same commit; `EvalCandidateHarvestTests` reads those columns and fails if they
vanish.
