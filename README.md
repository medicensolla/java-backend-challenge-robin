# Java Backend Challenge

A REST API for scheduling sports training sessions, assigning coaches and managing participant registrations. Built with Java 21, Spring Boot 3, PostgreSQL, Flyway and Lombok.

Try the deployed API through [Swagger UI](https://java-backend-challenge-robin-production.up.railway.app/swagger-ui/index.html#/Participants/createParticipant).

## Run locally

With Docker Desktop or Docker Engine running and Docker Compose v2 installed, run this from the repository root:

```sh
docker compose up --build
```

This starts the application and PostgreSQL, applies the migrations and checks the schema. No local Java or Maven installation is needed. The first build downloads images and dependencies.

Once both services are healthy:

- Open [local Swagger](http://localhost:8080/swagger-ui/index.html) to try the endpoints.
- Check `http://localhost:8080/actuator/health`; it should return `{"status":"UP"}`.
- The API contract is available at `/v3/api-docs` and in [api/openapi.json](api/openapi.json).

If port 8080 is occupied, use `PORT=18080 docker compose up --build` and change the port in the URLs.

PostgreSQL data stays in the `postgres_data` volume after `docker compose down`. To reset the local database, use `docker compose down --volumes`; this deletes its data. PostgreSQL is only exposed inside the Compose network.

### Run without Docker Compose

You need a Java 21 JDK and a reachable PostgreSQL database. Export these variables using your own connection details:

```sh
export DB_URL='jdbc:postgresql://localhost:5432/jbc'
export DB_USERNAME='your_database_user'
export DB_PASSWORD='your_database_password'
./mvnw spring-boot:run
```

The database user needs permission to apply migrations. `PORT` defaults to `8080`. The Maven Wrapper is included, so Maven does not need to be installed separately.

[.env.example](.env.example) contains the local configuration. Compose reads `.env` automatically; a direct Java process needs the variables exported. `DB_NAME` selects the Compose database name. Keep real credentials out of Git.

## Run the tests

With Java 21 selected, run the unit tests:

```sh
./mvnw test
```

For the full suite, including HTTP integration tests with PostgreSQL:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Docker must be running for Testcontainers. The tests create their own databases; you do not need to start the application or configure database credentials. They cover scheduling boundaries, duplicate and capacity rules, concurrent requests, cancellation, deletion and complete API journeys.

Reports are written to `target/surefire-reports/` and `target/failsafe-reports/`. Integration failures fail the build. [GitHub Actions](.github/workflows/ci.yaml) runs the same full suite on pull requests to `main` and pushes to `main`.

## Try the API

Swagger includes request examples. Start by creating a coach and a participant, then reuse their returned IDs to create a session and register the participant. List the registrations, cancel one, and delete the session once it is empty.

| Method | Endpoint | Purpose | Success |
| --- | --- | --- | --- |
| POST | `/api/coaches` | Create a coach | `201` |
| POST | `/api/participants` | Create a participant | `201` |
| POST | `/api/sessions` | Create a session | `201` |
| GET | `/api/sessions` | List sessions; optional `coachId`, `from` and `to` filters | `200` |
| POST | `/api/sessions/{sessionId}/registrations` | Register a participant | `201` |
| GET | `/api/sessions/{sessionId}/participants` | List registered participants | `200` |
| DELETE | `/api/sessions/{sessionId}/registrations/{registrationId}` | Cancel a registration | `204` |
| DELETE | `/api/sessions/{sessionId}` | Delete an empty session | `204` |

Every creation returns an `id`. A registration has its own ID, separate from the participant's; use it for cancellation. Successful deletes have no response body. Coaches and participants remain stored after a session is deleted, so repeating the same creation examples can return a duplicate conflict.

Both lists accept `page` (default `0`) and `size` (default `20`, maximum `100`). Responses contain `content`, `page`, `size`, `totalElements` and `totalPages`. Sessions sort by start time and ID; participants by ID.

Errors use RFC 9457 (`application/problem+json`), with a readable `detail` and a stable `code`. Invalid input returns `400`, missing resources `404`, and business conflicts `409`. Internal failures return a generic `500` without exposing exception details.

## Assumptions and decisions

- **Adjacent sessions are allowed.** Intervals are `[startTime, endTime)`, so one session can start exactly when another ends. Overlapping sessions for the same coach return `409 COACH_OVERLAP`.
- **Times require an explicit offset.** Inputs accept ISO-8601 with `Z` or an offset, and responses use UTC. Precision is truncated to PostgreSQL microseconds before validation. Past sessions are allowed.
- **Date filters select intersecting sessions.** Filters combine with AND; either date bound can be omitted. When both are present, `from` must be before `to`. Encode a `+` offset as `%2B` in query parameters.
- **Session deletion is blocked while registrations exist.** Cancel them first. Cancellation frees capacity; re-registering creates a new registration ID. Repeated cancellation returns `404`.
- **Capacity is enforced, with duplicates checked first.** A full session returns `409 SESSION_FULL`; an already registered participant receives `409 DUPLICATE_REGISTRATION`, even when it is full. Transactions lock the coach or session row to protect scheduling and registration checks against concurrent API requests.
- **People are checked by name and email together, separately per role.** Case and extra name whitespace are ignored; accents are preserved. The same combination can exist as both a coach and a participant. This extra rule is checked in Java, so simultaneous creations can still produce duplicates.
- **Coach names are split at the first word.** The remaining words become the last name; a single-word name has an empty last name. Public names have normalized whitespace. This is a practical convention, not a universal way to interpret personal names.

## Migration safety

Flyway owns schema changes; Hibernate validates the schema. V1 and V2 remain unchanged. V3 adds the separate coach-name fields while keeping the original `name` column. A trigger synchronizes both representations so previous and current application versions can coexist.

The [migration ADR](docs/ADR-001-coach-name-expansion.md) explains checksums, compatibility, backfill locks and application rollback. The legacy column stays until old writers are retired and the rollback window is closed.

## What I would improve with more time

- Add authentication, authorization and better operational monitoring before using this with real users.
- Measure query performance and lock contention as load grows. Requests for the same coach or session are serialized, so hot resources deserve attention before adding more application instances. I would also consider cursor pagination for large, changing lists.
- Make the name/email duplicate rule safe under concurrent creation if it becomes a firm requirement. For a large coach table, stage the name migration and backfill in batches.

## AI assistance

I defined the scope, wrote the tickets and planned the work in Markdown. I used Codex to carry out that plan, including implementation, tests and documentation. I reviewed the pull requests, checked the results and made the final decisions on the design and trade-offs.