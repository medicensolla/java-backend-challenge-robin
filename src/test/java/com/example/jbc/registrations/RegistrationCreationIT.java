package com.example.jbc.registrations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.jbc.sessions.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationCreationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionRepository sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private RegistrationRepository registrations;

    @Test
    void createsAPersistedRegistrationWithItsOwnId() {
        var sessionId = createSession(2);
        var participantId = createParticipant();

        var response = register(sessionId, participantId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(3);
        var id = UUID.fromString(body.path("id").asText());
        assertThat(id).isNotEqualTo(sessionId).isNotEqualTo(participantId);
        assertThat(body.path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(body.path("participantId").asText()).isEqualTo(participantId.toString());
        var stored = jdbc.queryForMap("SELECT session_id, participant_id FROM registrations WHERE id = ?", id);
        assertThat(stored.get("session_id")).isEqualTo(sessionId);
        assertThat(stored.get("participant_id")).isEqualTo(participantId);
        assertThat(count(sessionId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void rejectsDuplicatesEvenWhenTheSessionIsFull(int capacity) {
        var sessionId = createSession(capacity);
        var participantId = createParticipant();
        assertThat(register(sessionId, participantId).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(register(sessionId, participantId), HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION",
                "Participant is already registered for this session.");
        assertThat(count(sessionId)).isEqualTo(1);
    }

    @Test
    void acceptsTheLastSlotAndRejectsTheNextDistinctParticipant() {
        var sessionId = createSession(2);
        assertThat(register(sessionId, createParticipant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(register(sessionId, createParticipant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(register(sessionId, createParticipant()), HttpStatus.CONFLICT, "SESSION_FULL",
                "Session has reached its capacity.");
        assertThat(count(sessionId)).isEqualTo(2);
    }

    @Test
    void permitsTheSameParticipantInDifferentSessions() {
        var participantId = createParticipant();
        for (var sessionId : new UUID[]{createSession(1), createSession(1)}) {
            assertThat(register(sessionId, participantId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(count(sessionId)).isEqualTo(1);
        }
    }

    @Test
    void returnsMissingSessionBeforeCheckingTheParticipant() {
        var sessionId = UUID.randomUUID();
        assertError(register(sessionId, UUID.randomUUID()), HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                "Session was not found.");
        assertThat(count(sessionId)).isZero();
    }

    @Test
    void returnsMissingParticipantBeforeCheckingFullCapacity() {
        var sessionId = createSession(1);
        assertThat(register(sessionId, createParticipant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(register(sessionId, UUID.randomUUID()), HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND",
                "Participant was not found.");
        assertThat(count(sessionId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidInputBeforeResourceLookup(String description, String sessionId, String body) {
        var before = jdbc.queryForObject("SELECT count(*) FROM registrations", Long.class);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = http.postForEntity("/api/sessions/{sessionId}/registrations",
                new HttpEntity<>(body, headers), JsonNode.class, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().path("detail").asText()).isNotBlank();
        assertThat(response.getBody().has("trace")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registrations", Long.class)).isEqualTo(before);
    }

    @Test
    void translatesARealUniqueViolationAndRollsBackTheFailedInsertion() {
        var sessionId = createSession(2);
        var participantId = createParticipant();
        assertThat(register(sessionId, participantId).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Model a stale pre-check; the actual insert still goes to PostgreSQL and hits UNIQUE.
        doReturn(false).when(registrations).existsBySessionIdAndParticipantId(sessionId, participantId);
        assertError(register(sessionId, participantId), HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION",
                "Participant is already registered for this session.");
        assertThat(count(sessionId)).isEqualTo(1);

        // A fresh request can still occupy the remaining slot after the failed transaction.
        assertThat(register(sessionId, createParticipant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(sessionId)).isEqualTo(2);
    }

    @Test
    void documentsTheCreationResponseAndErrorStatuses() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        var operation = response.getBody().path("paths").path("/api/sessions/{sessionId}/registrations").path("post");
        assertThat(operation.path("operationId").asText()).isEqualTo("createRegistration");
        assertThat(operation.path("responses").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("201", "400", "404", "409", "500");
        assertThat(operation.path("responses").path("201").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo("#/components/schemas/RegistrationResponse");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void concurrentParticipantsCannotOverbookTheLastSlot(int capacity) throws Exception {
        var sessionId = createSession(capacity);
        for (int i = 1; i < capacity; i++) {
            assertThat(register(sessionId, createParticipant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertConcurrentRegistrations(sessionId, createParticipant(), createParticipant(), "SESSION_FULL",
                "Session has reached its capacity.");
        assertThat(count(sessionId)).isEqualTo(capacity);
    }

    @Test
    void concurrentDuplicateRetainsPrecedenceOverFullCapacity() throws Exception {
        var sessionId = createSession(1);
        var participantId = createParticipant();
        assertConcurrentRegistrations(sessionId, participantId, participantId, "DUPLICATE_REGISTRATION",
                "Participant is already registered for this session.");
        assertThat(count(sessionId)).isEqualTo(1);
    }

    private void assertConcurrentRegistrations(UUID sessionId, UUID firstParticipant, UUID secondParticipant,
                                               String conflictCode, String detail) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = new TransactionTemplate(transactionManager).execute(status -> {
                sessions.findByIdForUpdate(sessionId).orElseThrow();
                var first = executor.submit(() -> register(sessionId, firstParticipant));
                var second = executor.submit(() -> register(sessionId, secondParticipant));
                awaitSessionWaiters(2);
                return List.of(first, second);
            });
            assertThat(requests).isNotNull();
            var responses = List.of(requests.get(0).get(10, TimeUnit.SECONDS), requests.get(1).get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(ResponseEntity::getStatusCode)
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            var conflict = responses.stream().filter(response -> response.getStatusCode().equals(HttpStatus.CONFLICT))
                    .findFirst().orElseThrow();
            assertError(conflict, HttpStatus.CONFLICT, conflictCode, detail);
            assertThat(conflict.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            var created = responses.stream().filter(response -> response.getStatusCode().equals(HttpStatus.CREATED))
                    .findFirst().orElseThrow().getBody();
            assertThat(created).isNotNull();
            assertThat(jdbc.queryForObject("SELECT participant_id FROM registrations WHERE id = ?", UUID.class,
                    UUID.fromString(created.path("id").asText())))
                    .isEqualTo(UUID.fromString(created.path("participantId").asText()));
        }
    }

    @Test
    void lockingOneSessionDoesNotBlockRegistrationInAnother() throws Exception {
        var blockedSession = createSession(1);
        var otherSession = createSession(1);
        var participant = createParticipant();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var blocked = new TransactionTemplate(transactionManager).execute(status -> {
                sessions.findByIdForUpdate(blockedSession).orElseThrow();
                var pending = executor.submit(() -> register(blockedSession, participant));
                awaitSessionWaiters(1);
                var independent = executor.submit(() -> register(otherSession, participant));
                try {
                    assertThat(independent.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                } catch (Exception exception) {
                    throw new AssertionError("Registration in another session must complete while the lock is held", exception);
                }
                assertThat(pending.isDone()).isFalse();
                return pending;
            });
            assertThat(blocked).isNotNull();
            assertThat(blocked.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(count(blockedSession)).isEqualTo(1);
            assertThat(count(otherSession)).isEqualTo(1);
        }
    }

    private void awaitSessionWaiters(int expected) {
        // The holder transaction is released on either success or assertion failure.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND query ILIKE '%from sessions%' AND query ILIKE '%for%update%'
                        """, Integer.class)).isEqualTo(expected));
    }

    private UUID createSession(int capacity) {
        var coach = http.postForEntity("/api/coaches",
                Map.of("name", "Alex", "email", "alex-" + UUID.randomUUID() + "@example.com"), JsonNode.class);
        assertThat(coach.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(coach.getBody()).isNotNull();
        var response = http.postForEntity("/api/sessions", Map.of("coachId", coach.getBody().path("id").asText(),
                "startTime", "2026-10-05T14:00:00Z", "endTime", "2026-10-05T15:00:00Z",
                "capacity", capacity, "location", "Court A"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private UUID createParticipant() {
        var response = http.postForEntity("/api/participants",
                Map.of("name", "Sam", "email", "sam-" + UUID.randomUUID() + "@example.com"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private ResponseEntity<JsonNode> register(UUID sessionId, UUID participantId) {
        return http.postForEntity("/api/sessions/{sessionId}/registrations",
                Map.of("participantId", participantId.toString()), JsonNode.class, sessionId);
    }

    private long count(UUID sessionId) {
        return jdbc.queryForObject("SELECT count(*) FROM registrations WHERE session_id = ?", Long.class, sessionId);
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("detail").asText()).isEqualTo(message);
        assertThat(response.getBody().size()).isEqualTo(6);
    }

    private static Stream<Arguments> invalidRequests() {
        var sessionId = UUID.randomUUID().toString();
        var validBody = "{\"participantId\":\"" + UUID.randomUUID() + "\"}";
        return Stream.of(
                Arguments.of("invalid session UUID", "not-a-uuid", validBody),
                Arguments.of("missing participant", sessionId, "{}"),
                Arguments.of("null participant", sessionId, "{\"participantId\":null}"),
                Arguments.of("invalid participant UUID", sessionId, "{\"participantId\":\"not-a-uuid\"}"),
                Arguments.of("numeric participant", sessionId, "{\"participantId\":42}"),
                Arguments.of("malformed JSON", sessionId, "{\"participantId\":"),
                Arguments.of("null body", sessionId, "null"),
                Arguments.of("absent body", sessionId, null));
    }
}
