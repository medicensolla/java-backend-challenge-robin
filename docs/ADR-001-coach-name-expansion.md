# ADR-001 — Compatible coach-name expansion

Status: accepted. Date: 2026-10-02.

## Context and decision

V1 and V2 are released migrations. Flyway records their checksums; editing them would produce different histories for existing and new databases and fail validation. [V3](../src/main/resources/db/migration/V3__expand_coach_names.sql) adds `first_name` and `last_name`, backfills existing rows and makes both columns non-null while retaining `name`. Hibernate validates the schema instead of changing it. IDs, emails and relationships remain intact.

The public API continues to accept and return `{id,name,email}`. Coach names normalize the same six ASCII whitespace characters in Java and SQL: space, TAB, LF, VT, FF and CR. Leading/trailing whitespace is removed and runs become one space. The first word becomes `first_name`; the remainder becomes `last_name`, which is empty for a single word. This is a practical, culturally limited convention rather than universal personal-name parsing. Participant names and emails are unchanged. Unicode whitespace outside this set is not normalized.

## Coexistence and inconsistent writes

A row-level `BEFORE INSERT OR UPDATE` trigger changes `NEW` within the original transaction, without recursive updates. Older applications read/write `name`; the new JPA mapping writes the split fields and recomposes the public name. Split writers must supply both non-null fields, including an empty `last_name` when appropriate. Their composed full name is normalized and re-split using the same first-word convention.

On insertion, a legacy-only name is split and split-only fields recompose `name`. On updates, `IS DISTINCT FROM` identifies which representation actually changed and synchronizes the other. Email-only updates preserve names. When both representations are supplied or changed, they must agree after normalization; conflicting writes are rejected with SQLSTATE `23514`. Historical blank SQL names remain representable as empty strings; HTTP validation continues to require nonblank names.

## Deployment, rollback and contraction

Apply V3 before deploying the new application. Application rollback retains V3 and its trigger: the previous application can still read/write `name`. Do not undo schema history or edit old migrations. Older applications keep their original HTTP response behavior even though database writes are normalized. Restoring an earlier schema from backup would require handling data written after the backup; it is not an automatic rollback.

Dropping `name` and synchronization requires a later migration, removal of all old application instances, consumers and writers, consistent representations and closure of the application rollback window. This expansion deliberately keeps that compatibility path available.

## Locks and backfill cost

For the challenge's small dataset, V3 runs DDL, backfill and constraints in one transaction. `ALTER TABLE` takes locks held through commit; updating every coach generates writes and WAL. This does not promise a migration without a pause.

For large tables, stage expansion and synchronization, perform resumable backfill batches, then validate/add constraints before deploying the new mapping. Monitor lock duration, replication/WAL impact and rollout progress and establish lock timeouts. That production rollout machinery is not implemented here.

## Validation and references

PostgreSQL integration tests cover normalized HTTP responses, JPA reads, legacy and split writes/updates, email-only updates, inconsistent representations and session creation using coaches. A manual isolated V2-to-V3 upgrade checked normalized backfill, preserved IDs/emails/relationships, later legacy writes and old/new application coexistence. A separate clean startup applied all three migrations. There are no dedicated automated Flyway/migration tests; ordinary application startup applies and validates migrations.

See [PostgreSQL 17 trigger behavior](https://www.postgresql.org/docs/17/trigger-definition.html) and the [reviewer guide](../README.md) for setup and verification commands.
