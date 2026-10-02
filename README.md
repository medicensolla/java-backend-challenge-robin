# Java Backend Challenge

A Java 21 / Spring Boot API for scheduling training sessions and managing registrations, backed by PostgreSQL. All eight business endpoints below are implemented. This guide covers startup, testing and a complete HTTP walkthrough.

## Run with Docker Compose

Requirements: Docker Engine or Docker Desktop running, Docker Compose v2, an available host port (8080 by default), and `curl` for the walkthrough. The first build needs access to container registries and Maven dependencies. Run commands from the repository root. Java and Maven do not need to be installed on the host for this option.

```sh
docker compose up --build
```

Wait until both services are healthy, then use a second terminal:

```sh
curl -i http://localhost:8080/actuator/health
```

Expected: `200` with `{"status":"UP"}`. The application waits for PostgreSQL, applies Flyway migrations and validates the schema through Hibernate. Connection details are not exposed by health responses.

- [Swagger UI](http://localhost:8080/swagger-ui/index.html): interactive requests and DTO examples.
- [Generated OpenAPI](http://localhost:8080/v3/api-docs): documentation generated from the running application.
- [Versioned OpenAPI contract](api/openapi.json): examples, error codes and project decisions.
- [Coach-name migration ADR](documentation/ADR-001-coach-name-expansion.md): version compatibility, migration safety and rollback.

Compose binds the application to `127.0.0.1`. PostgreSQL is reachable inside the Compose network and **does not expose a host database port**. If 8080 is occupied, use `PORT=18080 docker compose up --build` and substitute that port in the URLs.

Database data lives in the project's named `postgres_data` volume. `docker compose down` stops/removes containers and retains that data. **`docker compose down --volumes` deletes this project's database data**; use it only when intentionally resetting your local environment. Changing database credentials in environment variables does not update users in an already initialized volume.

## Configuration and local Java startup

The stack uses Java 21, Spring Boot 3.5.16, PostgreSQL 17.9, Maven Wrapper 3.9.16, Spring Data JPA, Flyway, Lombok and springdoc. There is no host Maven installation requirement; use `./mvnw`.

| Variable | Docker Compose | Direct Java process |
| --- | --- | --- |
| `DB_NAME` | Database name; defaults to `jbc` | Not used by the application; include the database name in `DB_URL` |
| `DB_URL` | Set internally to `jdbc:postgresql://db:5432/${DB_NAME}` | JDBC URL; defaults to `jdbc:postgresql://localhost:5432/jbc` |
| `DB_USERNAME` | Defaults to `jbc_local` | Required |
| `DB_PASSWORD` | Defaults to `local_development_only` | Required |
| `PORT` | Published host port; defaults to 8080; container remains on 8080 | Application listening port; defaults to 8080 |

Defaults and [.env.example](.env.example) are for local development. Compose automatically reads a root `.env` file. Spring Boot does not automatically load that file; export its variables when running Java directly. Do not commit real credentials.

For local startup, select a **Java 21 JDK** (`java -version`), and create a PostgreSQL database/user reachable from the host. The user must be able to apply schema migrations. A separate local PostgreSQL installation or a separately published container works; the supplied Compose database alone is not reachable at `localhost:5432`.

```sh
cp .env.example .env
# Edit .env to match your reachable PostgreSQL database and credentials.
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Alternatively, with the same environment exported:

```sh
./mvnw --batch-mode --no-transfer-progress package -DskipTests
java -jar target/java-backend-challenge-0.0.1-SNAPSHOT.jar
```

## Tests and CI

Select Java 21 for host Maven commands. Unit/JSON tests do not require Docker:

```sh
./mvnw test
```

Run the complete suite, including real PostgreSQL HTTP integration tests:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Docker must be running and accessible to Testcontainers, which creates its own PostgreSQL containers. A separately started application/database and normal `DB_*` variables are not needed for these tests. The first run may download dependencies and PostgreSQL images.

Surefire publishes unit results under `target/surefire-reports/`; Failsafe publishes integration results under `target/failsafe-reports/`. Failsafe runs both `integration-test` and `verify`, so failing integration tests fail the build. Coverage includes validation, scheduling boundaries and coach overlap concurrency, registration capacity/duplicates and concurrent last-slot protection, cancellation/deletion, coach-name compatibility and complete HTTP journeys. There are no dedicated Flyway tests; migrations run during ordinary integration startup, and the existing-database upgrade was also checked manually in isolation.

[GitHub Actions](.github/workflows/ci.yaml) runs the same `verify` command with Java 21 and Docker for PRs targeting `main` and pushes to `main`. The job is named `Maven verify`; reports are uploaded on failure. Enforcing a passing check before merge requires an active GitHub branch protection rule or ruleset in addition to this workflow.

## Endpoints

All request/response bodies are JSON. Creation endpoints return the resource's generated UUID. Successful DELETE responses have **no body**. No authentication is configured.

| Method | Route | Success | Main errors |
| --- | --- | --- | --- |
| POST | `/api/coaches` | `201` `{id,name,email}` | `400 INVALID_REQUEST` |
| POST | `/api/participants` | `201` `{id,name,email}` | `400 INVALID_REQUEST` |
| POST | `/api/sessions` | `201` `{id,coachId,startTime,endTime,capacity,location}` | `400 INVALID_REQUEST`, `404 COACH_NOT_FOUND`, `409 COACH_OVERLAP` |
| GET | `/api/sessions?coachId=…&from=…&to=…` | `200` session page | `400 INVALID_REQUEST` |
| POST | `/api/sessions/{sessionId}/registrations` | `201` `{id,sessionId,participantId}` | `400 INVALID_REQUEST`, `404 SESSION_NOT_FOUND / PARTICIPANT_NOT_FOUND`, `409 DUPLICATE_REGISTRATION / SESSION_FULL` |
| GET | `/api/sessions/{sessionId}/participants` | `200` participant page | `400 INVALID_REQUEST`, `404 SESSION_NOT_FOUND` |
| DELETE | `/api/sessions/{sessionId}/registrations/{registrationId}` | `204` | `400 INVALID_REQUEST`, `404 SESSION_NOT_FOUND / REGISTRATION_NOT_FOUND` |
| DELETE | `/api/sessions/{sessionId}` | `204` | `400 INVALID_REQUEST`, `404 SESSION_NOT_FOUND`, `409 SESSION_HAS_REGISTRATIONS` |

Unexpected failures return `500 INTERNAL_ERROR` with a sanitized message. There are no separate GET-by-ID endpoints for coaches, participants or sessions; use creation responses, filtered session listing and registered participant listing.

Both GET lists accept `page` (zero-based, default `0`) and `size` (default `20`, maximum `100`). Omitted or empty values use defaults. Responses are `{content, page, size, totalElements, totalPages}`; `content` contains the same session or participant fields described above. Filters apply before pagination and totals. Sessions remain ordered by start time then UUID; participants by UUID. Invalid integers, negative pages and sizes outside `1–100` return `400 INVALID_REQUEST`, before checking session existence.

An empty result has `content: []`, `totalElements: 0`, and `totalPages: 0`. A page beyond the last retains the matching totals but has empty content. A missing session still returns `404` when listing participants. This replaces the previous array response, including requests without pagination parameters. Offset pagination does not provide a fixed snapshot across requests if records change between pages.

## Complete curl walkthrough

Run these commands in the same shell. `-i` displays the HTTP status and headers. After each creation, **copy the `id` from its JSON response into the indicated variable**, replacing the placeholder. The IDs are generated on every run; example UUIDs in Swagger are illustrative.

```sh
BASE_URL='http://localhost:8080'
```

Create a coach (`201`). The public name becomes `Alex Rivera`:

```sh
curl -i -X POST "$BASE_URL/api/coaches" \
  -H 'Content-Type: application/json' \
  -d '{"name":"  Alex   Rivera  ","email":"alex@example.com"}'
```

```sh
COACH_ID='<copy coach id>'
```

Create two participants (each `201`):

```sh
curl -i -X POST "$BASE_URL/api/participants" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Sam Lee","email":"sam@example.com"}'
```

```sh
PARTICIPANT_A_ID='<copy first participant id>'
```

```sh
curl -i -X POST "$BASE_URL/api/participants" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Taylor Kim","email":"taylor@example.com"}'
```

```sh
PARTICIPANT_B_ID='<copy second participant id>'
```

Create a one-place session (`201`). The response has `startTime: "2026-10-05T14:00:00Z"` and `endTime: "2026-10-05T15:00:00Z"`. Past dates are allowed, so this walkthrough remains usable later:

```sh
curl -i -X POST "$BASE_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"coachId\":\"$COACH_ID\",\"startTime\":\"2026-10-05T10:00:00-04:00\",\"endTime\":\"2026-10-05T11:00:00-04:00\",\"capacity\":1,\"location\":\"Court A\"}"
```

```sh
SESSION_ID='<copy session id>'
```

List with all three filters (`200`, including the created session). `--get --data-urlencode` encodes `+02:00` correctly; a literal `+` in a query string can otherwise become a space. This interval represents the same UTC instants:

```sh
curl -i --get "$BASE_URL/api/sessions" \
  --data-urlencode "coachId=$COACH_ID" \
  --data-urlencode 'from=2026-10-05T16:00:00+02:00' \
  --data-urlencode 'to=2026-10-05T17:00:00+02:00' \
  --data-urlencode 'page=0' \
  --data-urlencode 'size=20'
```

Register Sam (`201`), occupying the last available place. Save the **registration's own `id`**, which differs from the participant ID:

```sh
curl -i -X POST "$BASE_URL/api/sessions/$SESSION_ID/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"participantId\":\"$PARTICIPANT_A_ID\"}"
```

```sh
REGISTRATION_ID='<copy registration id>'
```

List participants (`200`). Sam's `id` is the participant ID; `registrationId` equals the registration just created:

```sh
curl -i "$BASE_URL/api/sessions/$SESSION_ID/participants?page=0&size=20"
```

Register Sam again: `409 DUPLICATE_REGISTRATION`, even though the session is now full:

```sh
curl -i -X POST "$BASE_URL/api/sessions/$SESSION_ID/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"participantId\":\"$PARTICIPANT_A_ID\"}"
```

Register Taylor: `409 SESSION_FULL`:

```sh
curl -i -X POST "$BASE_URL/api/sessions/$SESSION_ID/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"participantId\":\"$PARTICIPANT_B_ID\"}"
```

Try deleting the occupied session: `409 SESSION_HAS_REGISTRATIONS`:

```sh
curl -i -X DELETE "$BASE_URL/api/sessions/$SESSION_ID"
```

Cancel Sam (`204`, empty body), releasing the place. Repeating this cancellation returns `404 REGISTRATION_NOT_FOUND`:

```sh
curl -i -X DELETE "$BASE_URL/api/sessions/$SESSION_ID/registrations/$REGISTRATION_ID"
```

Register Sam again (`201`). Save the **new** registration ID; the cancelled ID is not reused:

```sh
curl -i -X POST "$BASE_URL/api/sessions/$SESSION_ID/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"participantId\":\"$PARTICIPANT_A_ID\"}"
```

```sh
REPLACEMENT_ID='<copy new registration id>'
```

Cancel the replacement (`204`), then register Taylor (`201`) to demonstrate capacity reuse by a different participant:

```sh
curl -i -X DELETE "$BASE_URL/api/sessions/$SESSION_ID/registrations/$REPLACEMENT_ID"
```

```sh
curl -i -X POST "$BASE_URL/api/sessions/$SESSION_ID/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"participantId\":\"$PARTICIPANT_B_ID\"}"
```

```sh
FINAL_REGISTRATION_ID='<copy Taylor registration id>'
```

Cancel Taylor (`204`), confirm an empty participant list (`200`, empty `content`), then delete the empty session (`204`). Coaches and participants remain stored:

```sh
curl -i -X DELETE "$BASE_URL/api/sessions/$SESSION_ID/registrations/$FINAL_REGISTRATION_ID"
```

```sh
curl -i "$BASE_URL/api/sessions/$SESSION_ID/participants?page=0&size=20"
```

```sh
curl -i -X DELETE "$BASE_URL/api/sessions/$SESSION_ID"
```

Listing participants for the deleted session now returns `404 SESSION_NOT_FOUND`:

```sh
curl -i "$BASE_URL/api/sessions/$SESSION_ID/participants?page=0&size=20"
```

## Rules and error format

- Session intervals are half-open `[startTime, endTime)`: adjacent sessions are allowed; overlapping sessions for the same coach are rejected. Different coaches can teach simultaneously. Application scheduling locks the coach row through commit, including when its schedule is empty.
- Inputs require ISO-8601 timestamps with `Z` or an explicit offset such as `-04:00`. Values are truncated, not rounded, to PostgreSQL microseconds before validation and comparisons. Responses use UTC `Z`; the original timezone is not retained. `startTime` must remain strictly before `endTime` after truncation. Numeric timestamps and offset-free timestamps are invalid.
- Session filters combine with AND and select interval intersections: `endTime > from` and `startTime < to`. Either bound may be omitted; when both are provided, `from < to` is required after truncation. An unknown coach returns empty content and zero totals. Sessions sort by start time then UUID; registered participants sort by participant UUID. Existing empty sessions return empty participant content and zero totals; missing sessions return `404`.
- Names, emails and location must be nonblank; emails must be valid; capacity must be a positive integer. Emails are not unique. Coach names collapse spaces, tabs, LF, VT, FF and CR and trim surrounding ASCII whitespace. A single-word name is supported. Participant names and emails are stored as supplied. Splitting a coach's first word from the remainder is a practical convention, not a universal interpretation of personal names.
- Input validation precedes resource checks. Registration locks the session row, then checks participant, duplicate and capacity, in that order. A duplicate takes precedence over a full session. The database UNIQUE constraint also prevents duplicate pairs under concurrent writes.
- Cancellation removes only the addressed session/registration relationship. A registration belonging to another session, missing registration or repeated cancellation returns `404 REGISTRATION_NOT_FOUND`; session existence is checked first. Deletion rejects a session with registrations; its foreign key also protects against orphan registrations. These operations do not delete people.

Spring MVC errors follow RFC 9457 with `Content-Type: application/problem+json`. They contain `type` (`about:blank`), the standard HTTP `title`, matching `status`, safe `detail`, request-path `instance` without query parameters, and the existing `code`. Clients must read `detail` instead of the removed top-level `message`. Successful responses keep their existing format. Body validation also includes sorted, deduplicated, nonempty `fieldErrors`, for example:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Request contains invalid fields.","instance":"/api/coaches","code":"INVALID_REQUEST","fieldErrors":[{"field":"email","message":"must be a valid email"}]}
```

Malformed JSON (including invalid UUIDs or timestamps in JSON bodies) uses `400 INVALID_REQUEST` and `Request body must contain valid JSON.` Malformed path/query UUIDs or query timestamps use `400 INVALID_REQUEST` and `Bad Request`. Invalid time windows have specific messages shown in OpenAPI. Domain errors omit `fieldErrors`; for example:

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"Session has reached its capacity.","instance":"/api/sessions/11111111-1111-4111-8111-111111111111/registrations","code":"SESSION_FULL"}
```

Unexpected failures return a `500` problem with `code: INTERNAL_ERROR` and `detail: An unexpected error occurred.` Internal exception details are logged only on the server. Framework errors preserve their HTTP status and headers, including `Allow` for 405 responses. Errors outside Spring MVC are not customized.

## Limits and possible improvements

Registration creation holds a pessimistic write lock on the session row until its transaction commits or rolls back. Under PostgreSQL READ COMMITTED, the next registration checks capacity after the previous transaction completes. Two distinct participants competing for the last slot receive one `201` and one `409 SESSION_FULL`; concurrent requests for the same participant receive one `201` and one `409 DUPLICATE_REGISTRATION`. Sessions lock independently, including across application instances sharing the database. The existing UNIQUE constraint remains a second defense against duplicate pairs.

Capacity protection applies to this application registration flow, not direct SQL writes. Cancellation remains independent: a concurrent cancellation may free a slot after a `SESSION_FULL` response. No capacity-update endpoint is provided.

Lists are paginated and Spring MVC errors use RFC 9457. Production authentication/authorization, deployment security and observability are possible follow-up work; they are not part of this challenge's implemented scope. Coach/participant update/delete, recurring sessions, payments and notifications are not provided. Coach-name contraction and large-table migration rollout require the safeguards described in the ADR. Final clean-clone/restart/submission verification is a separate delivery step.

## AI assistance and ownership

Codex assisted with requirement analysis, ticket planning, Java/SQL implementation, tests and local verification commands.
