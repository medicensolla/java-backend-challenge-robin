# JBC-001 — Decisions before implementation

These are adopted project choices, not additional requirements from the interviewer. The supplied plan is the source available for this ticket; the original PDF has not been independently checked.

| Topic | Decision |
| --- | --- |
| Stack | Java 21, Spring Boot 3.5.16, Maven 3.9.16, Wrapper 3.3.4, springdoc 2.9.1; remaining dependency and plugin versions managed by the Boot parent. PostgreSQL 17.9 Alpine for tests. |
| Architecture | One application/database, packages by feature, DTOs separate from JPA entities; no generic framework or additional service layers without need. |
| IDs | UUIDs, including a separate registration ID. Unique `(session_id, participant_id)` constraint. |
| Schema | Flyway owns DDL; Hibernate validates. Never edit released migrations. V1 contains coach `name`; V2 adds the other entities. |
| Time | `Instant` for comparisons, PostgreSQL `timestamptz`, UTC configuration. Explicit ISO-8601 string input offset required; return UTC `Z`. Scheduling normalizes to microsecond precision before checking/storing; reject collapsed windows and numeric timestamps. Do not preserve the original timezone. |
| Boundaries | Half-open session interval. Overlap: `existing.start < proposed.end && proposed.start < existing.end`. Adjacent intervals succeed. |
| Validation | Nonblank name/location/email, valid email, positive integer capacity, strict start before end. No ban on past sessions; no invented uniqueness rule for emails. UUID syntax errors return 400. |
| Filters | Optional coach/from/to; AND combination; intersection with `[from,to)`. Lower bound: `session.end > from`. Upper bound: `session.start < to`. Either bound alone is allowed; `from >= to` returns 400. Unknown coach filter yields an empty list. |
| Ordering | Sessions by `startTime`, then `id`; participant listing by participant ID for deterministic output. Unpaginated arrays in the baseline. |
| Deletion | Block deletion when registrations exist, 409. Restrictive foreign keys prevent orphan relationships. Missing session is 404. |
| Cancellation | Physically delete the registration. Verify session membership. Repeated cancellation or another session's registration returns 404. Re-registration after cancellation is allowed with a new ID. |
| Missing references | Create session with missing coach: 404. Register with missing session/participant: 404. List participants for missing session: 404; existing empty session: empty array. |
| Errors | JSON `{code,message}` with stable codes; optional field errors. No stack traces. 400 invalid input, 404 missing resource, 409 domain conflict; unexpected failures use a generic 500. RFC 9457 is optional. |
| Precedence | Parse/validate request before resource checks. Registration: session existence, participant existence, duplicate, capacity. Cancellation: session existence, then registration membership. Session deletion: existence, then registrations. |
| Coach name | Preserve public `name` during internal split. First token becomes first name, remaining tokens last name; collapse whitespace, single-token last name empty. This is a pragmatic, culturally imperfect interpretation. |
| Migration safety | JBC-012 expands with retained legacy name and compatibility synchronization, backfills, preserves API behavior, and writes an ADR. Defer destructive contraction until old versions and rollback dependency retire. Define conflicting representation writes in that ticket. |
| Scheduling concurrency | Lock the coach row before overlap check/insert within one transaction; locking only existing sessions misses an empty schedule. Verify against PostgreSQL in JBC-007. |
| Registration concurrency | Unique constraint handles duplicate races; baseline capacity check is transactional but does not guarantee concurrent capacity safety. JBC-017 adds per-session serialization if selected. |

Required capabilities and their endpoint/ticket mapping are in the contract's `x-requirements`. Required technical/migration/submission work is in `x-delivery`. Optional tickets remain explicitly unselected.

## Documentation availability

The owner finalized JBC-013 by moving the reviewer guide to root README.md and the ADR to documentation/ADR-001-coach-name-expansion.md, staging these files and asking to commit their changes too. Both deliverables are available in a clone. All docs/ remains ignored, including memories, this decisions history, ticket notes and the preexisting PR template moved there by the owner. The contract carries the same final locations.

## JBC-002 bootstrap choices

The application lives under `com.example.jbc`. Add feature packages when their implementations exist; avoid empty scaffolding. Slice JSON tests exercise UTC serialization and equivalent offsets. `ApplicationIT` starts a full HTTP server and uses PostgreSQL Testcontainers service connections, so normal database environment variables are unnecessary for tests. It checks HTTP OpenAPI availability, PostgreSQL connectivity, UTC sessions and JPA initialization. JBC-004 adds an HTTP health assertion and removes the explicit Flyway assertion. No test-only schema generation or auto-configuration exclusions are used. V1 stays scoped to JBC-003.

The official Maven `only-script` wrapper avoids a binary wrapper JAR; its Maven download is checksum-pinned. Java 21 is selected for verification; Maven compiles with release 21 via the Boot parent. Credentials come from environment variables, and `.env.example` contains explicitly local-only examples. Maven outputs and `.env.*` files are ignored, except the versioned example.

To satisfy the dependency sequence without remote operations, `main` was advanced locally to JBC-001 with a fast-forward before creating JBC-002. The owner still performs pushes.

Version sources consulted: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc Boot 3 documentation](https://springdoc.org/v2/), [Apache Wrapper](https://maven.apache.org/tools/wrapper/index.html), and published Maven Central metadata/POMs.

## JBC-003 initial schema

V1 creates only `coaches(id UUID PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL)`. PostgreSQL primary keys also reject null IDs. The application will generate UUIDs; no database UUID extension or default is needed. Names remain unsplit in V1. No email uniqueness, arbitrary text length, or application-level email validation is added to the database.

PostgreSQL migration tests apply only through target 1 in isolated schemas, preserving their meaning after later migrations arrive. They verify column types/nullability, primary-key and null constraints, duplicate-email acceptance, retained data/checksum, and zero reapplied migrations with a fresh Flyway instance. The existing full HTTP startup test also verifies application initialization with V1. Record the released SQL hash and use new migration files for future changes; do not repair checksums to conceal edits.

## JBC-004 scope correction and Compose

The owner explicitly considers dedicated Flyway/migration tests unnecessary for this exercise. This overrides the corresponding test recommendations in the supplied plan for current and future tickets. Removed `CoachesMigrationIT` and the explicit Flyway assertion from `ApplicationIT`. Earlier ticket records describe the checks that actually ran at the time; they are historical, not the current test scope. Continue using new versioned migrations, immutable V1, Hibernate validation, and Flyway's normal startup validation. Focus automated tests on business rules and HTTP behavior with PostgreSQL.

Compose runs the app and PostgreSQL 17.9 Alpine with a persistent named volume. The app builds inside Docker using the pinned Maven Wrapper on Java 21, then runs in a separate non-root Java 21 JRE image. A BuildKit cache retains Maven downloads. The allowlisted build context includes only the POM, Unix Wrapper/configuration and source tree; it excludes host target output, docs, Git history and root environment files.

The app waits for database health and exposes only aggregate `/actuator/health`, including the DB check with hidden details. The runtime's BusyBox wget checks that endpoint. The database healthcheck uses TCP pg_isready to avoid counting the temporary initialization server as ready. App port 8080 is published to loopback with configurable host PORT; PostgreSQL has no published host port. Local-only database defaults make the primary startup command work without prerequisite configuration.

No additional deployment platform, custom readiness controller, custom healthcheck script, or migration testing framework is added.

## JBC-005 schema and persistence

V2 adds participants, sessions and registrations while leaving V1 byte-for-byte unchanged. SQL enforces required references, positive capacity, start before end, a unique `(session_id, participant_id)` pair, and restrictive deletion. Session times use timestamptz; Java uses Instant. Names/emails/location remain text without invented length or email-uniqueness constraints.

Four feature packages hold plain JPA entities and Spring Data repositories: coaches, participants, sessions, registrations. Hibernate generates UUIDs in the application before inserting; the database has no UUID extension dependency. Relations are required lazy ManyToOne associations, with no cascading or bidirectional collections. Entities have a protected JPA constructor, explicit creation constructors and getters. Public DTOs/controllers will arrive with their respective API tickets. No base entity, generic service framework or speculative repository query methods were introduced. Lombok was added in the JBC-005 PR follow-up at the owner's request.

The two session indexes support planned filtering/order: `(coach_id, start_time, id)` and `(start_time, id)`. The unique registration index already supports per-session listing/count/existence and restrictive session deletion; do not add a redundant standalone session_id index. Additional indexes require actual access patterns. Overlap detection/locking and registration capacity counting are business-service work in JBC-007/JBC-009, not extra schema machinery.

A single persistence round-trip case was added to the existing PostgreSQL-backed ApplicationIT. It flushes and clears JPA's context before reading, so it verifies real database mappings rather than objects still held in memory. Its transaction rolls back after the test. It checks generated IDs, relationships and Instant timestamps at microsecond precision. No dedicated Flyway or schema-upgrade test was added, following the owner's override of the original plan.

## JBC-005 PR follow-up: Lombok

The owner explicitly requested Lombok before continuing with the next ticket. Coach, Participant, TrainingSession and Registration now use `@Getter` and `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. Public creation constructors remain explicit, excluding generated IDs. No `@Data`, setters, generated equals/hashCode, or toString involving lazy relations are introduced.

The Spring Boot parent manages Lombok 1.18.46. Maven declares it with provided scope, configures it explicitly as an annotation processor, and excludes it from the packaged Spring Boot runtime JAR. The explicit processor configuration follows [Lombok's Maven setup](https://projectlombok.org/setup/maven). No migration or HTTP contract changed.

## JBC-006 people creation APIs

Coach and participant features each have a record request DTO, record response DTO, a concrete transactional service and a controller. Lombok provides constructor injection. No service interface/implementation pair or generic people abstraction is needed for these simple operations. Bean Validation validates nonblank names/emails and email syntax at the HTTP boundary. Preserve the public coach name field for the future internal split. Store the supplied name/email unchanged; duplicate emails remain allowed.

Return 201 with the full `{id,name,email}` DTO. Do not advertise a Location pointing at a GET endpoint that does not exist. A shared RestControllerAdvice based on ResponseEntityExceptionHandler handles field validation, malformed JSON and framework HTTP errors while retaining their HTTP status. Field errors are deduplicated and ordered by field/message; rejected values are not returned. Unexpected exceptions are logged and return a generic 500. The baseline JSON error format remains in place; RFC 9457 is still optional.

PostgreSQL-backed HTTP tests cover both endpoints' persisted records and generated IDs, repeated emails, blank/missing fields, invalid emails and malformed JSON. Invalid fields must not persist records. Existing OpenAPI startup assertions also check 201/400/500 documentation. No new Flyway/migration test or schema change was added.

## JBC-014 moved into JBC-006

The owner selected the optional CI ticket earlier than its proposed dependency order and requested it in JBC-006. This is an authorized exception to one branch/PR per ticket; do not recreate the same workflow later under a separate JBC-014 branch. The same verify command will automatically run future tests as they are added. No extra framework or CI-only database setup is needed.

The stable check name is Maven verify. PR events use GitHub's normal pull_request execution rather than pull_request_target. There are no path filters, conditions skipping the verification job, continue-on-error or cache-hit-based skips. Concurrency cancels superseded runs. A fifteen-minute job timeout bounds stalled builds. Pin official checkout v7/setup-java v6/upload-artifact v7 at verified tag SHA revisions. Workflow validation used actionlint 1.7.12 downloaded to /tmp and checksum-verified.

The owner still handles pushes. Requiring a green check for merge additionally needs branch protection/rules in GitHub; repository API read returned 404 without authentication, so protection state is unknown and no remote settings were modified.


## JBC-007 session creation and overlap protection

POST /api/sessions validates required coach ID/start/end/capacity and nonblank location. Capacity must be a positive integer; Jackson float-to-integer coercion is disabled. A small field deserializer converts offset ISO-8601 strings into Instant, rejecting local timestamps, numeric epochs and malformed values. Normalize both instants by truncating to PostgreSQL microsecond precision before checking start < end, overlap or insertion. This keeps the checked interval identical to the persisted and returned interval, including sub-microsecond boundary cases. Past windows remain allowed.

SessionService uses a single transaction: validate window, select coach with PESSIMISTIC_WRITE, execute the derived Spring Data exists query (coach ID AND start < requested end AND end > requested start), persist and return a DTO. Lock the stable coach row even when there are no sessions to lock. The lock remains held through transaction commit, so the next request observes the committed insertion under PostgreSQL's default READ COMMITTED isolation. Different coaches lock different rows. All application session creation must use this service; raw SQL bypasses the scheduling guarantee. No exclusion-constraint migration or separate scheduling abstraction is introduced.

Use a small common ApiException carrying status/code/message for controlled business errors: invalid interval 400 INVALID_REQUEST, missing coach 404 COACH_NOT_FOUND, overlap 409 COACH_OVERLAP. Bean Validation executes before resource lookup. The shared handler retains the JSON error contract and sanitized unexpected failures. Creation includes all session fields and ID with UTC output; no nonexistent Location URL is advertised.

Service unit tests cover meaningful control flow, interval validity/precision, existence-before-overlap precedence, conflict rejection without insertion, and lock/check/insert ordering. The actual overlap predicate and adjacency boundaries are tested through HTTP against PostgreSQL instead of duplicating the database predicate in an unused Java helper. Concurrency coverage holds the coach row in a third independent transaction, submits two HTTP requests, waits until PostgreSQL reports both transactions waiting on the coach lock, then commits the holder; expect exactly one 201, one 409 and one persisted session. There is no synchronization by arbitrary sleep or mocked persistence. Flyway/migration-only tests remain excluded, and V1/V2 are untouched.

References: [Spring Data JPA locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html), [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html). No remote GitHub state or settings were changed. JBC-006 is already merged into main locally; the owner's screenshot confirms the private repository's branch protection is currently not enforced under its plan. Actions runs independently of that protection.


## JBC-008 session listing and interval filters

GET /api/sessions binds optional UUID coachId and ISO-8601 OffsetDateTime from/to query parameters using Spring MVC conversion and DateTimeFormat.ISO.DATE_TIME. Local timestamps and numeric epochs fail conversion with the existing safe 400 INVALID_REQUEST format. Convert offsets to Instant and normalize bounds to PostgreSQL microsecond precision, consistent with JBC-007. Reject non-increasing normalized ranges before querying; one-sided ranges are valid. Empty query values follow Spring's standard optional conversion and mean omitted. Clients must URL-encode + in positive offsets.

SessionService.list is read-only transactional. A small inline Spring Data Specification adds coach_id equality, end_time > from and start_time < to only when each value exists, combining them with AND. Keep the predicate beside this single use instead of introducing a filter framework or many repository methods for all combinations. Specifications avoid nullable OR clauses and parameter-typing issues; Hibernate builds the actual query. Sort by startTime then id. Missing coaches are collection filters and yield [], with no lookup or 404. Return the same SessionResponse shape and UTC values as creation; reuse a private DTO mapper.

The repository uses an EntityGraph for coach while listing, so reading coach IDs cannot trigger additional lazy-load queries. Entity relations remain lazy generally; only this read loads the required coach alongside sessions. A PostgreSQL HTTP test verifies one prepared statement for a schedule spanning two coaches. The rest of the API tests use a fresh fixture in their isolated container before each case, including intentionally out-of-order inserts and deterministic IDs for tied start times. They verify the actual database predicate through HTTP rather than mocking the criteria builder. Unit coverage validates equal/reversed/collapsed filter ranges before persistence access. No schema or migration tests are added, no migrations are edited and pagination remains optional JBC-015.


## JBC-009 registration creation, duplicates and capacity

A record request requires a UUID participantId and a response record returns the registration's own generated id alongside sessionId and participantId. The controller binds UUID sessionId, validates the request before service access and uses the existing ApiException/ApiError handler. Lombok provides constructor injection in the concrete service/controller. No service interface, extra mapper framework or new dependency is introduced.

The single create transaction checks session existence, participant existence, duplicate pair and capacity in that order, then inserts. The duplicate check precedes capacity so repeating a registration in a full session consistently returns DUPLICATE_REGISTRATION. Reject count >= capacity; count == capacity - 1 is accepted. Repository methods derive the existence and scoped count queries. saveAndFlush exposes PostgreSQL constraint violations before leaving the service; inspect the cause chain for Hibernate ConstraintViolationException with the exact existing uq_registrations_session_participant name. Translate only that violation into the duplicate ApiException; rethrow all others for sanitized INTERNAL_ERROR handling. The runtime exception causes rollback, and no database query is made after the failed flush.

The PostgreSQL UNIQUE constraint prevents duplicate pairs under races. Capacity is deliberately the agreed transactional baseline; a transaction alone does not serialize count/check/insert between distinct participants. Concurrency capacity protection remains optional JBC-017. No row locking or migration is added here; V1 and V2 are unchanged.

Service tests assert error precedence, first/last slots, full/overfull rejection, exact constraint identification and rethrow of unrelated failures. HTTP tests verify generated IDs through persisted rows, input/resource errors, duplicates in full sessions, capacity boundaries and Swagger. For the UNIQUE fallback test only, a repository spy returns a stale false existence result after a real first insertion. The second insert executes against PostgreSQL and violates the actual UNIQUE constraint; expect 409 and unchanged row count, then a distinct participant can occupy the remaining place. This tests the persistence fallback and rollback without a flaky simultaneous-HTTP race. No dedicated Flyway tests.


## JBC-010 listing, cancellation and protected session deletion

Reuse RegistrationService/SessionService and controller families. RegistrationController's common path is now /api/sessions/{sessionId}; existing POST creation stays at /registrations unchanged. A record RegisteredParticipantResponse exposes only id/name/email/registrationId. Read-only listing checks session existence and fetches scoped registrations ordered by participant ID through an EntityGraph for participant. It performs an existence query plus one joined query rather than lazy loads per participant.

Cancellation checks session existence then executes JPQL DELETE constrained by session.id and registration.id. The affected row count distinguishes missing/wrong-session/already-cancelled registrations and handles another deletion between the check and write. It does not load entities or cascade to participants. Both operations live in one transaction; callers get 204 with no body or the established 404 codes/messages. Reinscription generates a new UUID, and cancelled capacity becomes available.

Session deletion checks session existence then registration existence, rejecting nonempty sessions. Use an explicit JPQL DELETE by session UUID and inspect affected rows; zero rows yield SESSION_NOT_FOUND. Bulk JPQL executes SQL immediately, so the FK error is raised inside the service rather than deferred to commit. Walk DataIntegrityViolationException causes and translate only Hibernate ConstraintViolationException named fk_registrations_session into SESSION_HAS_REGISTRATIONS. The existing restrictive FK rejects a newly added relationship after a stale pre-check. Throw ApiException so the failed transaction rolls back; do not query inside that failed transaction. Keep other failures for the sanitized shared 500 handler. No generic exception-classifier framework, cascading deletes, schema migration or capacity locks.

PostgreSQL API coverage resets its own container fixture between cases and exercises listing/ordering, scoped/repeated cancellation, capacity reuse/reinscription, blocking deletion until all cancellations, preservation of people and unrelated relationships, 400/404 errors and bodyless 204 responses. Hibernate statistics verifies listing uses two statements including existence, independent of the number of participants. A spy stubs only the registration existence pre-check to false while a real registration is persisted: the actual session delete hits PostgreSQL's FK, returns 409 and leaves both records intact; subsequent cancellation and deletion succeed. An unrelated injected persistence failure also returns sanitized INTERNAL_ERROR without private details. No Flyway-only tests.


## JBC-011 complete HTTP scheduling journeys

Add one SchedulingJourneyIT class in the application's root test package, using the established random-port SpringBootTest, TestRestTemplate, ServiceConnection and PostgreSQL Testcontainers configuration. Three independent scenarios cover an entire successful schedule/registration lifecycle, conflict rejection and invalid inputs/missing references. This complements the per-feature cases instead of copying their parameterized validation/boundary matrices.

Every coach, participant, session and registration fixture is created through real HTTP and addressed using returned UUIDs. Database access is limited to ordered DELETE cleanup of registrations, sessions, participants and coaches in the class's own container before each test. The test class is not transactional, so requests exercise actual application commits and rollbacks. No mocked repositories or H2 are used. Private helpers only handle common HTTP requests and readable response assertions; no shared testing framework or new dependencies.

The successful case sends an explicit negative offset and checks both UTC timestamps after creation and filtered retrieval. Add an adjacent later session for the same coach and a session in the same interval for another coach; combined coach/from/to filters must include only the addressed session. Strictly encode URI variables, including + in a positive offset. Verify participant details/registration IDs, cancellation, reuse of a freed place, empty participant listing, bodyless cancellation/session-deletion 204 responses, disappearance from the same filter and a 404 after deletion. Conflict and invalid-request journeys finish by re-reading the original session and registrations to establish that failed requests did not mutate them.

The existing Maven Failsafe integration-test/verify execution and CI verify command already discover the new IT class. Do not change pom.xml or CI merely to add a matching test class. No API, production code or migrations change. Notes remain in ignored docs/. Concurrent registration capacity protection remains optional JBC-017 and coach name evolution stays JBC-012.


## JBC-012 compatible coach-name expansion

The owner chose normalized public coach names: "  Alex   Rivera  " returns "Alex Rivera". Coach now maps first_name and last_name, preserves its public creation constructor and explicitly recomposes getName(); Lombok provides the other getters and protected JPA constructor. The legacy name column is deliberately not mapped by new JPA. HTTP requests/responses remain name-based; no split fields are added to public DTOs.

V3 adds split columns, backfills them and canonical legacy names, then marks split columns NOT NULL while retaining name and every existing ID/email/relationship. SQL and Java normalize the same six ASCII whitespace characters; preserve accented/non-Latin letters. Collapse whitespace and strip surrounding ASCII spaces, split on the first space, use an empty remainder for a single word. SQL composes split input into the full name and re-splits by this policy, keeping representation boundaries canonical. This is a practical convention rather than universal name parsing; Unicode whitespace outside the stated set is not normalized.

A SQL normalization function and one BEFORE INSERT OR UPDATE trigger synchronize legacy-only and split-only writes. INSERT distinguishes representations by non-null supplied fields; UPDATE detects actual changes with IS DISTINCT FROM. Both representations supplied/changed must agree after normalization or raise SQLSTATE 23514. Split writers provide both non-null fields (empty last_name allowed). Email-only updates leave names untouched. The trigger modifies NEW instead of issuing recursive UPDATEs. Old blank SQL names remain representable as empty split strings; API NotBlank validation continues separately.

The migration runs transactionally on PostgreSQL: ALTER TABLE holds locks through commit, so concurrent old writers cannot observe an incomplete expansion. The challenge's whole-table backfill is appropriate for its small dataset; large deployments would stage and batch it. Retain the schema and synchronization for application rollback. Contraction waits until all old consumers and rollback dependencies retire. See ADR-001-coach-name-expansion.md. V1/V2 are immutable and unchanged.

CoachTest covers split/recomposition and whitespace. CoachCompatibilityIT runs against current PostgreSQL with normal application migrations and tests actual legacy/new inserts and updates, email-only writes, matching and conflicting simultaneous representations, fresh JPA reads, scheduling through HTTP and API/Swagger compatibility. It does not seed V2 or invoke Flyway migration APIs. Upgrade/backfill checks are manual in isolated Docker environments, preserving the owner's no-Flyway-tests preference. Working notes remain ignored in docs/. JBC-013 publishes the ADR and reviewer README at the final locations selected by the owner.


## JBC-013 reviewer documentation

The owner selected root README.md and documentation/ADR-001-coach-name-expansion.md as the final public locations and retained the /docs/ ignore rule. Runtime Swagger metadata/examples and static OpenAPI document existing contracts. README curl commands were executed against an isolated Docker Compose project. The owner shortened AI disclosure and moved the template to ignored docs/; preserve those edits. References and relative links were corrected after the moves. Capacity concurrency remains an explicit limitation and final delivery/restart verification remains JBC-019.

## JBC-020 duplicate people

The owner added name/email combination duplicate rejection separately within coaches and participants, while allowing the same combination across roles. Ignore case and normalize the six ASCII whitespace characters in names; trim surrounding ASCII whitespace in emails, preserve accents and public values. Current HTTP validation still runs first. Services query repository EXISTS and return 409 DUPLICATE_COACH or DUPLICATE_PARTICIPANT before saving. Reuse normalize_coach_name for query comparison; do not load all people. The owner chose no UNIQUE, migration or new locks and explicitly accepted concurrent-create races. Existing duplicate data remains intact. See JBC-020.md for actual tests and isolated Compose results.
