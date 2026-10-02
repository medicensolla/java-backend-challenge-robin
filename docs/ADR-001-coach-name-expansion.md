# ADR-001 — Splitting coach names safely

Status: accepted. Date: 2026-10-02.

## Why released migrations stay unchanged

Once a migration has run, it becomes part of the database's history. Flyway stores its checksum. Editing it would break validation on existing databases, while new databases would receive a different schema under the same version. We keep V1 and V2 unchanged and introduce the name split in V3.

## How the deployment stays compatible

V3 adds `first_name` and `last_name`, fills them from existing names and keeps the original `name` column. IDs, emails and relationships stay intact. The API still accepts and returns `name`.

A database trigger keeps both representations in sync on inserts and updates. The previous application can continue reading and writing `name`, while the new version uses the separate fields. Conflicting writes are rejected rather than letting the two representations disagree.

We apply V3 before deploying the new application. If we need to roll back the application, the expanded schema stays in place. Removing `name` will require a later migration, after all old versions and writers are retired and the rollback window has closed.

The backfill can temporarily block writes. For a large table, we would stage the changes and process existing rows in batches.
