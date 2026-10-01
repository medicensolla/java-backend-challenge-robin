# Java Backend Challenge: Requirements and Ticket Plan

Source: `java-backend-challenge.md.pdf`, “Senior Java Backend Developer — Take-Home Exercise”, sections 1–7.

This document translates the assignment into an implementation backlog. **Source requirements** below describe what the challenge requests. **Proposed decisions** and ticket-level implementation details are recommendations for this submission, not additional instructions from the interviewer. No repository, branches, commits, issues, or pull requests have been created by this document.

## 1. Objective and scope

Build a self-contained REST API for a sports training facility that creates training sessions, assigns coaches, and manages participant registrations while preventing coach double-bookings and over-capacity sessions.

The evaluation emphasizes API contracts, data modeling, testing, migration safety, and Git hygiene. Prefer a smaller, well-tested implementation over a broad, untested one. Authentication, a frontend, notifications, payments, recurring sessions, and coach/participant update or delete endpoints are outside the required scope.

## 2. Source requirements

### Domain model

| Entity | Required information |
| --- | --- |
| Coach | `id`, `name`, `email`; subsequently evolve the name through the migration exercise |
| Session | `id`, `coachId`, `startTime`, `endTime`, `capacity`, `location` |
| Participant | `id`, `name`, `email` |
| Registration | Relationship between a participant and a session |

The full schema is the candidate's responsibility.

### Required capabilities and behavior

- Create coaches and participants.
- Create sessions with a coach, time window, capacity, and location.
- List sessions with filters for coach and date range.
- Delete a session.
- Register a participant for a session.
- List participants registered for a session.
- Cancel a registration.
- Every successful creation response must include at least an `id` in its body, including registration creation.
- Reject overlapping sessions for the same coach with `409 Conflict` and a clear error body.
- Reject registrations beyond session capacity with `409 Conflict`.
- Reject duplicate participant/session registrations with `409 Conflict`.
- For session deletion, either cascade-delete registrations or block deletion while registrations exist. Choose one and document it in the README.
- Persist session timestamps as PostgreSQL `timestamptz`. Accept and return ISO-8601 timestamps with an explicit offset, compare instants in UTC, and configure UTC handling.
- Define whether adjacent sessions overlap when one ends exactly when another starts. Document the choice and test it.

### Mandatory technical requirements

- Java **17 or 21** and **Spring Boot 3**.
- PostgreSQL and versioned Flyway migrations. Do not use `ddl-auto: update`.
- Unit tests for business logic, including overlap detection, capacity checks, and interval boundaries.
- At least one API end-to-end integration test backed by PostgreSQL through Testcontainers.
- A Dockerized application: `docker compose up --build` from the repository root must start both application and database. This is the reviewer's primary execution path.
- An OpenAPI/Swagger specification or Postman collection usable without reading implementation code; springdoc is recommended by the assignment.

### Mandatory migration exercise

1. Create `V1` with a `coaches` table containing the original `name` column.
2. Once a `V2` or later migration exists, treat `V1` as immutable, as though already deployed.
3. Add a new versioned migration to split `coach.name` into `first_name` and `last_name` using expand/contract or an equivalent backward-compatible approach.
4. Write a short ADR, approximately half a page, explaining why released migrations are immutable and how the change remains safe for a live database while the previous application version is still running.

### Submission and documentation

Submit a Git repository link. A private repository is acceptable if reviewer access is granted. Keep a normal history with feature-sized commits and sensible messages.

The repository must include working code, a README, the API contract, and the migration ADR. The README must explain startup, tests, assumptions, what would change with more time, and AI usage if applicable. AI assistance is allowed: disclose the tool, what it helped with, and what you decided yourself. Be prepared to explain the trade-offs.

Document ambiguous or contradictory requirements in the README or `QUESTIONS.md` before implementing the interpretation.

**Automatic disqualification:** startup fails with `docker compose up --build`, or `V1` was modified after later migrations were added.

### Optional enhancements explicitly listed in the source

- GitHub Actions CI running the test suite.
- Pagination on the session list endpoint.
- Structured errors, for example RFC 9457 `application/problem+json`.
- Correct behavior under concurrent registration attempts.
- README scalability notes for 10x or 100x load.

## 3. Proposed implementation decisions

Record these decisions in the repository during JBC-001. They are defaults for this plan, not requirements imposed by the PDF.

| Topic | Proposed choice and rationale |
| --- | --- |
| Stack | Java 21, Spring Boot 3, Maven Wrapper, Spring Web, Bean Validation, Spring Data JPA, PostgreSQL, Flyway, JUnit, Testcontainers, and springdoc. Select compatible concrete versions during bootstrap. |
| Architecture | One application and one database. Organize by feature, with controllers, application services, repositories, entities, and request/response DTOs. Avoid exposing JPA entities directly. |
| Identifiers | UUID IDs; give registrations their own ID and a unique constraint on `(session_id, participant_id)`. |
| Schema ownership | Flyway owns the schema; use Hibernate validation rather than schema generation. Applied migrations remain unchanged. |
| Time | Use Java `Instant` for internal comparisons; validate that input includes an offset and return normalized UTC timestamps ending in `Z`. PostgreSQL `timestamptz` represents an instant, not the original timezone. |
| Session interval | Half-open `[startTime, endTime)`: adjacent sessions are allowed. Overlap is `existing.start < proposed.end && proposed.start < existing.end`. |
| Validation | Require nonblank names/location, valid nonblank emails, positive capacity, and `startTime < endTime`. Do not invent a ban on past sessions. |
| Email identity | Do not assume email uniqueness; IDs identify coaches and participants. Document this since the source does not specify an email uniqueness rule. |
| Date filter | Optional `from`/`to` offset timestamps select sessions whose intervals intersect `[from, to)`. One bound is allowed. Reject `from >= to` when both are supplied. Combine coach and date filters with AND. |
| Ordering | Stable ordering by `startTime`, then `id`. |
| Session deletion | Block deletion while registrations exist, returning `409`; allow deletion after cancellation. Use restrictive foreign keys as a final safeguard. |
| Cancellation | Delete the registration relationship; no status/history model is required. A repeated cancellation returns `404`. |
| Missing resources | `404` for missing referenced coach/participant/session or addressed registration. A collection filter matching no resources returns an empty list. |
| Errors | Baseline JSON with a stable error code and readable message. RFC 9457 is an optional improvement. Never return stack traces. |
| Public coach name | Keep the required `name` API field during the internal schema split. Compose/decompose it at the application boundary; avoid an unnecessary public API breaking change. |

### Proposed API contract

| Method | Endpoint | Success | Main behavior |
| --- | --- | --- | --- |
| POST | `/api/coaches` | `201` + body with `id` | Accept `name`, `email` |
| POST | `/api/participants` | `201` + body with `id` | Accept `name`, `email` |
| POST | `/api/sessions` | `201` + body with `id` | Accept `coachId`, `startTime`, `endTime`, `capacity`, `location` |
| GET | `/api/sessions?coachId=…&from=…&to=…` | `200` | List sessions using the documented filters |
| DELETE | `/api/sessions/{sessionId}` | `204` | `409` when registrations exist |
| POST | `/api/sessions/{sessionId}/registrations` | `201` + body with registration `id` | Accept `participantId`; `409` for duplicate or full session |
| GET | `/api/sessions/{sessionId}/participants` | `200` | Include participant information and `registrationId` so cancellation is discoverable |
| DELETE | `/api/sessions/{sessionId}/registrations/{registrationId}` | `204` | Verify the registration belongs to the addressed session |

Use `400` for malformed payloads, invalid fields, timestamps without an offset, and invalid filter intervals. Document response examples and error precedence. For a duplicate attempt against a full session, prefer the duplicate-registration error; both conditions require `409`.

## 4. Git, branch, commit, and PR conventions

### Ticket and branch naming

- Ticket IDs start at **JBC-001**, then JBC-002, JBC-003, and so on.
- The branch name must be **exactly the ticket ID**: `JBC-001`.
- Do not add prefixes, descriptions, or suffixes: `feature/JBC-001` and `JBC-001-bootstrap` do not follow this convention.
- Use one branch and one PR per ticket. Multiple focused commits per ticket are encouraged.
- Use `main` as the integration branch. Start a ticket after its dependencies have merged; this keeps PRs easy to review without stacked branches.
- Keep the ticket IDs stable if optional work is skipped. A GitHub issue's numeric ID is separate from the `JBC-xxx` identifier.

Example lifecycle after `main` exists:

```bash
git switch main
git pull --ff-only origin main
git switch -c JBC-003
# Implement and inspect the changes; stage only relevant paths.
git add src/main/resources/db/migration/V1__create_coaches.sql
git diff --cached
git commit -m "feat(db): JBC-003 create initial coaches schema"
git push -u origin JBC-003
```

### Conventional Commits

Use:

```text
<type>(<scope>): JBC-<number> <imperative description>
```

Use `feat`, `fix`, `test`, `docs`, `refactor`, `build`, `ci`, or `chore` as appropriate. A scope is recommended; the ticket ID is mandatory. Keep subjects specific, in English, and without a trailing period.

```text
feat(sessions): JBC-007 reject overlapping coach assignments
test(sessions): JBC-007 cover adjacent time windows
fix(registrations): JBC-010 reject cancellation under another session
docs(adr): JBC-012 explain coach name migration compatibility
```

Avoid `WIP`, `final changes`, unrelated cleanup, and large commits that combine independent tickets. Reserve `!` and a `BREAKING CHANGE:` footer for actual breaking changes; the name-split exercise should preserve compatibility.

### Pull requests and merge history

Use a PR title matching the same pattern, such as:

```text
feat(sessions): JBC-007 add conflict-safe session creation
```

The platform assigns the PR number; do not try to make `#7` equal `JBC-007`. Include the ticket ID in the title and body. If GitHub issues are used, title the issue `JBC-007: Create sessions and prevent coach overlap` and use `Closes #<actual issue number>` in the PR body.

Suggested PR template:

```markdown
## Ticket
JBC-007

## Problem and behavior
Describe the requirement and the resulting observable behavior.

## Implementation and decisions
Explain only the changes and trade-offs relevant to review.

## Validation
List commands actually executed and their results. State any checks not run.

## Database compatibility
List new migrations and upgrade effects, or state that none are present.
Confirm that previously applied migrations were not edited.

## Acceptance criteria
Copy the ticket checklist and mark only verified items complete.
```

Prefer preserving the focused commits with a merge commit. Squash merging a single ticket is acceptable if its final message follows the convention. Never squash the entire submission into one final commit or rewrite history to hide edits to released migrations. Merge JBC-003 before adding V2 in JBC-005; that history should make V1's immutability visible.

## 5. Backlog and execution order

All tickets begin in **To do**. “Required” identifies delivery work covering mandatory source requirements; some engineering choices within those tickets remain recommendations.

| Ticket / exact branch | Title | Priority | Dependencies |
| --- | --- | --- | --- |
| JBC-001 | Record requirements, decisions, and API contract | Required | None |
| JBC-002 | Bootstrap application and repeatable build | Required | JBC-001 |
| JBC-003 | Introduce immutable V1 coaches schema | Required | JBC-002 |
| JBC-004 | Establish Docker Compose startup | Required | JBC-003 |
| JBC-005 | Add remaining domain schema in V2 | Required | JBC-003 |
| JBC-006 | Create coaches and participants | Required | JBC-005 |
| JBC-007 | Create sessions and prevent coach overlap | Required | JBC-006 |
| JBC-008 | List and filter sessions | Required | JBC-007 |
| JBC-009 | Register participants with integrity rules | Required | JBC-007 |
| JBC-010 | List participants, cancel registrations, delete sessions | Required | JBC-009 |
| JBC-011 | Verify complete API journeys with Testcontainers | Required | JBC-008, JBC-010 |
| JBC-012 | Evolve coach names safely and write migration ADR | Required | JBC-011 |
| JBC-013 | Finalize API documentation and submission README | Required | JBC-004, JBC-012 |
| JBC-014 | Add GitHub Actions checks | Optional | JBC-011 |
| JBC-015 | Add session-list pagination | Optional | JBC-008 |
| JBC-016 | Standardize RFC 9457 problem responses | Optional | JBC-010 |
| JBC-017 | Harden concurrent registration behavior | Optional | JBC-011 |
| JBC-018 | Document 10x and 100x scaling trade-offs | Optional | JBC-012 |
| JBC-019 | Perform clean submission verification | Required | JBC-013 and every selected optional ticket |

Execute required tickets first, then selected optional tickets, then JBC-019. JBC-004 deliberately checks the primary run path early. Tests accompany each behavior ticket; JBC-011 adds cross-feature journeys rather than postponing all testing until the end.

### JBC-001 — Record requirements, decisions, and API contract

**Branch:** `JBC-001` · **PR title:** `docs(plan): JBC-001 define requirements and API decisions`

Create the initial README, `QUESTIONS.md` or a decisions section, and a draft API contract. Record all choices from section 3, especially interval boundaries, date filtering, deletion, name compatibility, and validation.

Acceptance criteria:

- [ ] Every required capability is mapped to an endpoint and a ticket.
- [ ] Request/response examples include creation IDs and expected error statuses.
- [ ] Ambiguous behavior is explicitly resolved before its implementation.
- [ ] Required and optional work remain distinguishable.

Suggested commit: `docs(plan): JBC-001 define challenge scope and API contract`

### JBC-002 — Bootstrap application and repeatable build

**Branch:** `JBC-002` · **PR title:** `build(app): JBC-002 bootstrap Java Spring Boot service`

Create the Maven Wrapper, package structure, application entry point, dependencies, and environment-based configuration. Pin compatible versions and ignore generated files and local credentials. Initialize unit/integration test execution so `./mvnw verify` includes both.

Acceptance criteria:

- [ ] The project uses Java 17 or 21 and Spring Boot 3; the selected version is documented.
- [ ] `./mvnw verify` runs the initial build and tests reproducibly.
- [ ] PostgreSQL/Flyway/Testcontainers dependencies are configured.
- [ ] Schema auto-update is disabled; final persistence configuration uses schema validation.
- [ ] Real credentials are not committed; local example configuration is documented.

Suggested commit: `build(app): JBC-002 initialize Spring Boot and Maven Wrapper`

### JBC-003 — Introduce immutable V1 coaches schema

**Branch:** `JBC-003` · **PR title:** `feat(db): JBC-003 create initial coaches schema`

Add `V1__create_coaches.sql` with `id`, `name`, and `email`. Include appropriate primary-key and nullability constraints. Establish a PostgreSQL Testcontainers migration smoke test. Merge this ticket before adding V2.

Acceptance criteria:

- [ ] Flyway applies V1 to an empty PostgreSQL database.
- [ ] `coaches.name` exists in V1; the split is not introduced prematurely.
- [ ] Restarting against the migrated database succeeds without reapplying V1.
- [ ] The V1 file is preserved unchanged in all subsequent tickets.

Suggested commit: `feat(db): JBC-003 create initial coaches schema`

### JBC-004 — Establish Docker Compose startup

**Branch:** `JBC-004` · **PR title:** `build(docker): JBC-004 run service and PostgreSQL with Compose`

Add a Dockerfile, `.dockerignore`, root Compose file, database readiness checks, and documented configuration. Build the application inside Docker so the main run path does not require a local JDK. Provide a simple application readiness signal.

Acceptance criteria:

- [ ] `docker compose up --build` starts app and database from the repository root.
- [ ] The app connects to the Compose database and applies migrations.
- [ ] A documented HTTP request confirms that the application is ready.
- [ ] Restarting with the same database volume works.
- [ ] Normal shutdown and a clearly labeled destructive local reset command are documented.

Suggested commit: `build(docker): JBC-004 add reproducible Compose environment`

### JBC-005 — Add remaining domain schema in V2

**Branch:** `JBC-005` · **PR title:** `feat(db): JBC-005 add scheduling and registration schema`

Add V2 for participants, sessions, and registrations. Define foreign keys, unique participant/session registration, positive capacity, valid time windows, and indexes supporting actual access patterns. Map persistence entities and repositories.

Acceptance criteria:

- [ ] Session timestamps use `timestamptz`.
- [ ] Invalid references, duplicate registrations, nonpositive capacity, and invalid windows are constrained by the database where applicable.
- [ ] Session deletion cannot leave orphan registrations; the FK supports the chosen blocking policy.
- [ ] Tests cover both empty-database migration and V1-to-V2 upgrade.
- [ ] V1's contents and Flyway checksum remain unchanged.

Suggested commit: `feat(db): JBC-005 add session participant and registration tables`

### JBC-006 — Create coaches and participants

**Branch:** `JBC-006` · **PR title:** `feat(people): JBC-006 create coaches and participants`

Implement creation DTOs, validation, services, controllers, and baseline error handling. Retain the coach `name` API contract for the subsequent migration exercise.

Acceptance criteria:

- [ ] Both POST endpoints persist valid records and return `201` with an `id`.
- [ ] Blank required fields and invalid emails return documented `400` responses.
- [ ] Responses do not expose persistence internals or stack traces.
- [ ] API tests prove successful creation and invalid-input behavior against PostgreSQL.

Suggested commit: `feat(people): JBC-006 add coach and participant creation APIs`

### JBC-007 — Create sessions and prevent coach overlap

**Branch:** `JBC-007` · **PR title:** `feat(sessions): JBC-007 add conflict-safe session creation`

Implement creation with coach validation, capacity/window validation, and the documented overlap predicate. Use one transaction for the check and insert. As a recommended safeguard, serialize creation for a coach by locking its row before checking overlaps; document why locking only existing sessions misses an initially empty schedule.

Acceptance criteria:

- [ ] Valid creation returns `201` with a session `id`; missing coach returns `404`.
- [ ] Identical windows, partial intersections, and containment for one coach return `409` with a clear error.
- [ ] Adjacent windows succeed; the same window for different coaches succeeds.
- [ ] Invalid capacity, reversed/equal times, and timestamps without offsets return `400`.
- [ ] Equivalent instants expressed using different offsets behave identically and responses use UTC.
- [ ] Unit tests cover the overlap predicate and boundary cases; API tests verify persistence and statuses.
- [ ] If the recommended lock is adopted, a PostgreSQL concurrency test verifies simultaneous conflicting creations cannot both succeed.

Suggested commits:

- `feat(sessions): JBC-007 validate and create coach sessions`
- `test(sessions): JBC-007 cover overlap boundaries and UTC offsets`

### JBC-008 — List and filter sessions

**Branch:** `JBC-008` · **PR title:** `feat(sessions): JBC-008 list sessions by coach and interval`

Implement optional coach, lower-time, and upper-time filters with stable ordering. Keep pagination out of this ticket.

Acceptance criteria:

- [ ] Unfiltered, coach-only, date-only, and combined queries return correct results.
- [ ] One-sided ranges work; invalid ranges and offset-free timestamps return `400`.
- [ ] Sessions intersecting the filter interval are included; sessions touching only an excluded boundary are not.
- [ ] No matches return an empty list, and response ordering is deterministic.
- [ ] Repository/API tests cover boundaries and different timestamp offsets.

Suggested commit: `feat(sessions): JBC-008 add coach and date range filters`

### JBC-009 — Register participants with integrity rules

**Branch:** `JBC-009` · **PR title:** `feat(registrations): JBC-009 enforce capacity and uniqueness`

Implement registration creation with existence checks, duplicate detection, capacity validation, and database uniqueness enforcement. Keep check-and-insert in one transaction. Explicitly document that a transaction alone does not guarantee capacity under concurrent requests; JBC-017 adds that optional guarantee.

Acceptance criteria:

- [ ] Registration returns `201` with its own `id` and persists the relationship.
- [ ] Missing session or participant returns `404`.
- [ ] A repeated participant/session pair returns `409`, including at full capacity.
- [ ] The registration filling the last slot succeeds; the next distinct participant receives `409`.
- [ ] Unit tests cover capacity and duplicate rules; PostgreSQL API tests cover constraints and status mapping.
- [ ] A database unique-constraint violation is translated into a clear conflict response.

Suggested commit: `feat(registrations): JBC-009 enforce capacity and duplicate rules`

### JBC-010 — List participants, cancel registrations, delete sessions

**Branch:** `JBC-010` · **PR title:** `feat(registrations): JBC-010 add cancellation and safe session deletion`

Complete the relationship lifecycle and blocking deletion policy. Distinguish an empty existing session from a missing session. Translate FK conflicts from concurrent relationship changes into the documented conflict response.

Acceptance criteria:

- [ ] Participant listing returns participant data and registration IDs; an existing empty session returns an empty list.
- [ ] Cancellation removes only the addressed relationship and returns `204`.
- [ ] A missing registration, missing session, or registration under a different session returns `404`.
- [ ] Cancellation frees capacity, and registering again afterward is permitted.
- [ ] Deletion with registrations returns `409`; deletion after all cancellations returns `204`.
- [ ] Missing session deletion returns `404`; no operation leaves orphan relationships.
- [ ] API tests verify the complete lifecycle and negative cases.

Suggested commit: `feat(registrations): JBC-010 add listing cancellation and protected deletion`

### JBC-011 — Verify complete API journeys with Testcontainers

**Branch:** `JBC-011` · **PR title:** `test(api): JBC-011 verify scheduling journeys with PostgreSQL`

Add full HTTP integration coverage using a running Spring Boot application on a random port and PostgreSQL Testcontainers. Do not substitute H2 or mocked repositories. Reuse existing lower-level coverage.

Acceptance criteria:

- [ ] One end-to-end journey creates coach and participants, creates a session, filters it, registers participants, lists them, cancels registrations, and deletes the session.
- [ ] Negative journeys verify overlap, duplicate registration, full capacity, and blocked deletion with `409` responses.
- [ ] Creation IDs, error bodies, UTC round trips, and relevant `400`/`404` cases are asserted.
- [ ] `./mvnw verify` actually discovers and executes the integration tests and fails on test failures.
- [ ] Test isolation prevents ordering dependencies and reliance on a developer's database.

Suggested commit: `test(api): JBC-011 add end-to-end scheduling journeys`

### JBC-012 — Evolve coach names safely and write migration ADR

**Branch:** `JBC-012` · **PR title:** `feat(db): JBC-012 expand coach names with live-version compatibility`

Add `V3__expand_coach_names.sql` without editing V1 or V2. Add `first_name` and `last_name`, retain `name`, backfill existing data, and provide database-level synchronization for legacy writes. Update the new application to use the split fields internally while preserving the public `name` contract.

Proposed split policy: trim/collapse whitespace, place the first token in `first_name`, and the remainder in `last_name`; use an empty last name for a single-token name. This is deliberately simple and culturally imperfect. Preserve the legacy column during transition and document the policy instead of presenting it as universal name parsing.

Compatibility design:

- Old-version inserts/updates that write only `name` must populate or refresh the split columns after backfill, using a compatibility trigger or an equivalently proven mechanism.
- New-version writes to split fields must keep legacy `name` populated so old readers and its existing NOT NULL constraint continue to work.
- Define deterministic behavior for writes supplying both representations; reject inconsistent values or apply a documented precedence rule.
- Do not ship an automatically applied destructive contract migration in the same rollout. Retain `name` until old instances are retired and rollback no longer requires it.
- Describe the future contract migration and deployment gates in the ADR. A safe expand phase plus an explicit deferred contract is this plan's interpretation of the exercise.

Acceptance criteria:

- [ ] Tests seed a V2 database, apply V3, and verify multi-token names, single-token names, whitespace, IDs, and email preservation.
- [ ] Fresh installation through all migrations succeeds.
- [ ] Old-style INSERT and UPDATE statements work after V3, including new rows written after backfill.
- [ ] New-style writes remain readable through `name`, and the current API journey still passes.
- [ ] V1 and V2 remain byte-for-byte unchanged; Flyway validation succeeds.
- [ ] The ADR explains checksums/immutability, mixed-version reads and writes, rollback limits, and when contraction is safe.
- [ ] The ADR acknowledges migration locks and large-table backfill cost; it describes batching for production-scale data without unnecessarily implementing a migration platform.

Suggested commits:

- `feat(db): JBC-012 expand coach names with legacy write compatibility`
- `test(db): JBC-012 verify name migration and mixed-version writes`
- `docs(adr): JBC-012 explain expand and contract deployment`

### JBC-013 — Finalize API documentation and submission README

**Branch:** `JBC-013` · **PR title:** `docs(api): JBC-013 document execution contracts and trade-offs`

Complete a working OpenAPI/Swagger specification using springdoc or the selected alternative. Update examples against the implemented API. Document local run, Docker run, test prerequisites, actual commands, assumptions, migration ADR, limitations, and future work.

Acceptance criteria:

- [ ] A reviewer can discover and exercise all required endpoints without reading source code.
- [ ] Contracts include payloads, creation IDs, filters, UTC handling, and errors.
- [ ] README explains interval boundaries, deletion policy, date filtering, validation, and name splitting.
- [ ] README documents how to run unit and integration tests, including Docker requirements for Testcontainers.
- [ ] AI disclosure is truthful; for this plan, disclose AI-assisted requirement breakdown and ticket planning, then update it for any later assistance.
- [ ] Known limitations and what would change with more time are stated explicitly.

Suggested commit: `docs(api): JBC-013 complete OpenAPI and reviewer guide`

### JBC-014 — Add GitHub Actions checks (optional)

**Branch:** `JBC-014` · **PR title:** `ci(build): JBC-014 verify pull requests with Maven`

Acceptance criteria:

- [ ] PRs and `main` pushes run `./mvnw verify` with the chosen Java version and Docker available for Testcontainers.
- [ ] Test reports are available on failure; dependency caching does not hide failures.
- [ ] Workflow permissions are minimal and no private credentials are needed for the test suite.

Suggested commit: `ci(build): JBC-014 run unit and integration tests on GitHub Actions`

### JBC-015 — Add session-list pagination (optional)

**Branch:** `JBC-015` · **PR title:** `feat(sessions): JBC-015 paginate filtered session results`

Acceptance criteria:

- [ ] Page/size defaults, size limits, invalid values, and response metadata are documented.
- [ ] Pagination composes with coach/date filters and stable ordering.
- [ ] Tests cover empty pages and page boundaries; OpenAPI and clients/examples reflect the response-shape change.

Suggested commit: `feat(sessions): JBC-015 add deterministic session pagination`

### JBC-016 — Standardize RFC 9457 problem responses (optional)

**Branch:** `JBC-016` · **PR title:** `feat(errors): JBC-016 expose consistent problem details`

Acceptance criteria:

- [ ] Relevant errors use `application/problem+json` with consistent fields and stable business error codes.
- [ ] Validation, missing resources, and conflicts retain their intended HTTP statuses.
- [ ] Unexpected errors do not expose internals; tests and API examples match the new contract.

Suggested commit: `feat(errors): JBC-016 standardize API problem responses`

### JBC-017 — Harden concurrent registration behavior (optional)

**Branch:** `JBC-017` · **PR title:** `fix(registrations): JBC-017 prevent concurrent capacity overflow`

Lock the session row before capacity evaluation and registration insertion, or use another demonstrated atomic approach. Define consistent locking order for creation, cancellation, and deletion paths that need synchronization. Preserve the unique registration constraint.

Acceptance criteria:

- [ ] A synchronized test with distinct participants competing for the final slot yields exactly one success and one `409`; persisted count never exceeds capacity.
- [ ] Concurrent attempts for the same participant produce exactly one registration and a documented conflict.
- [ ] Tests use independent transactions/connections against PostgreSQL, not only mocks or sleeps.
- [ ] Cancellation/deletion interactions preserve integrity and return documented responses.
- [ ] The README explains the guarantee, lock contention, and limits of this approach.

Suggested commits:

- `fix(registrations): JBC-017 serialize capacity checks per session`
- `test(registrations): JBC-017 verify concurrent registration conflicts`

### JBC-018 — Document 10x and 100x scaling trade-offs (optional)

**Branch:** `JBC-018` · **PR title:** `docs(architecture): JBC-018 explain scaling constraints`

Acceptance criteria:

- [ ] Notes discuss indexed filters, query plans, bounded pagination, connection pools, and contention around popular sessions.
- [ ] Notes distinguish read scaling from consistency-sensitive writes and acknowledge multiple app instances.
- [ ] Observability and large-table migration approaches are described.
- [ ] Estimates are labeled as assumptions; no performance claim is presented as a measurement unless actually measured.

Suggested commit: `docs(architecture): JBC-018 describe scaling and operational trade-offs`

### JBC-019 — Perform clean submission verification

**Branch:** `JBC-019` · **PR title:** `docs(release): JBC-019 record submission verification`

Verify the final candidate in a clean checkout and an isolated Compose project/database volume. Record actual results and remaining limitations in a concise submission checklist; never fabricate green checks. Fix discovered defects in appropriately scoped commits before completing this ticket.

Acceptance criteria:

- [ ] `docker compose up --build` starts cleanly from the repository root without a prebuilt local JAR.
- [ ] Documented API requests work against that running environment.
- [ ] Restarting with persisted data works; a separate fresh database migrates successfully.
- [ ] `./mvnw verify` passes, including PostgreSQL Testcontainers tests and migration upgrade coverage.
- [ ] `git log --follow -- src/main/resources/db/migration/V1__create_coaches.sql` and relevant diffs confirm V1 was never changed after later migrations were introduced.
- [ ] README, API contract, ADR, AI disclosure, and source code agree.
- [ ] Branch names, commit messages, and PR titles follow the ticket conventions.
- [ ] The final ticket is merged; selected optional changes are included in verification.
- [ ] A repository URL is ready, and reviewer access is verified if the repository is private.

Suggested commit: `docs(release): JBC-019 record clean submission checks`

## 6. Definition of done for every ticket

- The ticket's acceptance criteria are verified and its scope is complete.
- Changes are limited to the ticket; meaningful commits use its ID.
- Tests accompany behavior changes and cover important failure paths.
- Relevant tests pass; final integration uses `./mvnw verify`.
- API, README, and ADR content are updated when behavior or decisions change.
- Schema changes use a new migration, never edits to released migrations.
- No generated build output, credentials, debug noise, or unjustified dependencies are committed.
- The PR records actual validation and any remaining limitations.

## 7. Requirement traceability

| Source requirement | Primary tickets |
| --- | --- |
| Create coaches and participants | JBC-006 |
| Create sessions with coach, window, capacity, location | JBC-007 |
| List sessions by coach and date range | JBC-008 |
| Register and list participants; cancel registration | JBC-009, JBC-010 |
| Return IDs for successful creations | JBC-006, JBC-007, JBC-009, JBC-011 |
| No coach overlap, `409`, tested boundary convention | JBC-007 |
| Capacity and duplicate registration conflicts | JBC-009 |
| Safe deletion and documented policy | JBC-005, JBC-010, JBC-013 |
| `timestamptz`, offset timestamps, UTC | JBC-005, JBC-007, JBC-008 |
| Java 17/21, Spring Boot 3, PostgreSQL, Flyway | JBC-002, JBC-003, JBC-005 |
| Business unit tests and Testcontainers API coverage | JBC-007, JBC-009, JBC-011 |
| Root Docker Compose startup | JBC-004, JBC-019 |
| OpenAPI/Swagger or Postman | JBC-001, JBC-013 |
| Immutable V1, compatible name split, short ADR | JBC-003, JBC-012, JBC-019 |
| README, assumptions, ambiguity decisions, future work, AI disclosure | JBC-001, JBC-013 |
| Normal Git history and accessible repository submission | All tickets; JBC-019 final check |
| Optional CI, pagination, structured errors, concurrency, scaling | JBC-014 through JBC-018 |
