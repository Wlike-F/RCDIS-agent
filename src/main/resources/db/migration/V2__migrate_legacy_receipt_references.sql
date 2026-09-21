-- Migrate legacy publicly-addressed receipt references to authenticated file metadata.
-- Binary bytes remain on disk; this migration stores only bounded metadata and ownership.

create index if not exists idx_uploaded_file_owner_category
    on uploaded_file (owner_user_id, category, deleted);

create table if not exists legacy_file_migration_issue
(
    id                    bigserial primary key,
    reimbursement_item_id bigint not null,
    legacy_url            varchar(500) not null,
    file_name             varchar(255),
    reason                varchar(64) not null,
    detail                varchar(500),
    resolved              boolean not null default false,
    resolved_at           timestamptz,
    created_at            timestamptz not null default now(),
    constraint uk_legacy_file_migration_issue_item unique (reimbursement_item_id)
);

-- Record malformed legacy references. They stay inaccessible because /uploads/** is not exposed.
insert into legacy_file_migration_issue (reimbursement_item_id, legacy_url, reason, detail)
select ri.id,
       ri.receipt_file,
       'INVALID_LEGACY_REFERENCE',
       'Legacy URL does not contain a supported UUID image file name; manual review is required.'
from reimbursement_item ri
where ri.receipt_file like '%/uploads/receipts/%'
  and lower(split_part(split_part(regexp_replace(ri.receipt_file, '^.*/', ''), '?', 1), '#', 1))
      !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|gif|webp|bmp)$'
on conflict (reimbursement_item_id) do nothing;

-- A file referenced by multiple applicants has ambiguous ownership. Quarantine it explicitly;
-- never rewrite it to the authenticated API until an administrator resolves the owner.
with legacy_refs as (
    select ri.id as item_id,
           ro.applicant,
           lower(split_part(split_part(regexp_replace(ri.receipt_file, '^.*/', ''), '?', 1), '#', 1)) as file_name,
           ri.receipt_file as legacy_url
    from reimbursement_item ri
    join reimbursement_order ro on ro.id = ri.reimbursement_id
    where ri.receipt_file like '%/uploads/receipts/%'
), ambiguous_files as (
    select file_name
    from legacy_refs
    where file_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|gif|webp|bmp)$'
    group by file_name
    having count(distinct applicant) <> 1
)
insert into legacy_file_migration_issue (reimbursement_item_id, legacy_url, file_name, reason, detail)
select lr.item_id,
       lr.legacy_url,
       lr.file_name,
       'AMBIGUOUS_OWNER',
       'The same file is referenced by multiple applicants; ownership must be assigned manually.'
from legacy_refs lr
join ambiguous_files af on af.file_name = lr.file_name
on conflict (reimbursement_item_id) do nothing;

with legacy_refs as (
    select ro.applicant,
           lower(split_part(split_part(regexp_replace(ri.receipt_file, '^.*/', ''), '?', 1), '#', 1)) as file_name
    from reimbursement_item ri
    join reimbursement_order ro on ro.id = ri.reimbursement_id
    where ri.receipt_file like '%/uploads/receipts/%'
), ownership as (
    select file_name,
           case when count(distinct applicant) = 1 then min(applicant) else '__UNRESOLVED__' end as owner_user_id,
           case when count(distinct applicant) = 1 then 'RECEIPT' else 'RECEIPT_QUARANTINED' end as category
    from legacy_refs
    where file_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|gif|webp|bmp)$'
    group by file_name
)
insert into uploaded_file
    (owner_user_id, category, file_name, stored_path, mime, created_by, deleted)
select owner_user_id,
       category,
       file_name,
       'uploads/receipts/' || file_name,
       case
           when file_name like '%.jpg' or file_name like '%.jpeg' then 'image/jpeg'
           when file_name like '%.png' then 'image/png'
           when file_name like '%.gif' then 'image/gif'
           when file_name like '%.webp' then 'image/webp'
           when file_name like '%.bmp' then 'image/bmp'
       end,
       case when owner_user_id = '__UNRESOLVED__' then 'flyway-v2-quarantine' else owner_user_id end,
       0
from ownership
on conflict (file_name) do nothing;

-- Only uniquely-owned references become authenticated URLs. Quarantined and malformed references
-- remain inaccessible and visible in legacy_file_migration_issue for explicit remediation.
update reimbursement_item ri
set receipt_file = '/api/files/receipts/'
        || lower(split_part(split_part(regexp_replace(ri.receipt_file, '^.*/', ''), '?', 1), '#', 1))
        || '/content',
    updated_at = now(),
    updated_by = coalesce(ri.updated_by, 'flyway-v2')
from reimbursement_order ro,
     uploaded_file uf
where ro.id = ri.reimbursement_id
  and uf.file_name = lower(split_part(split_part(regexp_replace(ri.receipt_file, '^.*/', ''), '?', 1), '#', 1))
  and uf.category = 'RECEIPT'
  and uf.owner_user_id = ro.applicant
  and ri.receipt_file like '%/uploads/receipts/%';

comment on table legacy_file_migration_issue is
    'Legacy receipt references withheld from authenticated serving until ownership is resolved.';
