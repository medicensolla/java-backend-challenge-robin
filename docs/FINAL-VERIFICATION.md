# JBC-019 — Final delivery verification

Verified locally on **2026-10-02 (America/Caracas)** against commit `6599b7cc8d374c18c966240797b8f2319bf8a676` on `main`, including JBC-017. Result: **PASS**. This report and the associated README/OpenAPI delivery metadata are documentation-only additions after that verification; no runtime fixes were needed.

## Environment and isolation

- Host: macOS / Darwin arm64; Microsoft OpenJDK 21.0.11 for Maven.
- Docker Engine 29.7.2; Docker Compose 5.4.0.
- Compose runtime: Temurin 21.0.12.1; PostgreSQL 17.9 Alpine.
- Local clone: `/tmp/jbc019.vjGIkk/repo`, created with `git clone --no-hardlinks` from the working repository. Only committed files were checked out; no `.env`, previous `target/`, or ignored working files were copied. Dependency/build caches were allowed; this was not a cold-cache benchmark.
- Isolated Compose project: `jbc019-vjgikk`, port `18199`, new volume `jbc019-vjgikk_postgres_data`. Existing developer stacks were not used.

## Commands and results

Commands below ran from the clean clone with Java 21 selected for Maven:

```sh
./mvnw --batch-mode --no-transfer-progress verify
PORT=18199 docker compose -p jbc019-vjgikk up --build -d --wait
curl --fail http://localhost:18199/actuator/health
curl --fail http://localhost:18199/swagger-ui/index.html
curl --fail http://localhost:18199/v3/api-docs
```

Maven reported **BUILD SUCCESS: 210 tests, 0 failures, 0 errors, 0 skipped**: 52 unit/JSON cases and 158 PostgreSQL integration cases. Both Compose services became healthy. Health returned `200` with `{"status":"UP"}`; Swagger UI and generated OpenAPI were accessible. Actuator uses its vendor JSON media type; business success responses use `application/json`.

The README walkthrough was executed through curl requests with automatically captured IDs: coach-name normalization; two participants; a capacity-one session with offset-to-UTC conversion; combined coach/date filters and pagination; registration and participant listing; duplicate, full-capacity and occupied-session deletion conflicts; cancellation and repeated cancellation; re-registration with a new ID; capacity reuse by the other participant; empty listing, successful deletion and subsequent 404. Invalid pagination returned 400. Errors carried `application/problem+json`, matching HTTP status, safe detail, stable code and path-only instance; 204 responses had no body.

Static and generated OpenAPI agreed on documented business operations, combined path/operation parameters, response statuses, media types, and request/response field names, nesting and JSON types. Descriptions and schema component names were not required to be textually identical. README local links resolved, the migration ADR was present, and `mvnw` was executable in the clone.

## Persistence and migration evidence

A separate final fixture retained a coach, participants, session and registration. IDs and HTTP representations were saved. Database rows and Flyway history were captured before and after recreating containers:

```sh
docker compose -p jbc019-vjgikk exec -T db pg_dump -U jbc_local -d jbc --data-only --column-inserts
docker compose -p jbc019-vjgikk exec -T db psql -U jbc_local -d jbc -Atc \
  'SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank'
docker compose -p jbc019-vjgikk down
PORT=18199 docker compose -p jbc019-vjgikk up -d --wait
```

All dumped INSERT rows matched before/after, including IDs, relationships and Flyway records. Session and participant-list HTTP responses matched the saved fixture. Restart logs reported successful validation of three migrations, schema version 3 and `No migration necessary.`

| Migration | Original commit | Flyway checksum | SHA-256, unchanged from original |
| --- | --- | --- | --- |
| V1 | `2935991` | `-2133938248` | `0d288f2693d659b0ab6f11abcc6d4623dbe718617e4c618d75fcf9e256bc5477` |
| V2 | `eb21d82` | `-1532345895` | `d5199dd70415912af17a8aeeeffdb2df73e57a6ab0408c4bb72a0064648b5dcb` |
| V3 | `a69a61d` | `-631367712` | `010430893f25f7ca870073cf7c7151d12b1508cd94e116c3a8a931a6980d8cbc` |

Each migration was compared byte-for-byte against its original commit. All three Flyway entries remained successful with identical checksums after recreation. This exercise verifies fresh installation and restart; it does not repeat the historical V2-to-V3 rollout described in the ADR.

## Cleanup and limits

The isolated verification containers, network and volume were removed using `docker compose -p jbc019-vjgikk down --volumes`; the temporary clone and scratch evidence were removed after recording these results. Shared dependency caches and Docker images were retained.

These are local results, not confirmation of GitHub Actions or branch-protection settings. No push, PR, external submission or publication was performed. JBC-018 remains optional and unselected. Production security, load benchmarks and deployment certification are outside this verification.
