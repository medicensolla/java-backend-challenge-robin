package com.example.jbc.registrations;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationLifecycleIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private ObjectMapper mapper;
    @MockitoSpyBean
    private RegistrationRepository registrations;

    @BeforeEach
    void clearFixture() {
        jdbc.update("DELETE FROM registrations");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM participants");
        jdbc.update("DELETE FROM coaches");
    }

    @Test
    void anExistingEmptySessionReturnsAnEmptyArray() {
        var session = createSession(2);
        var response = list(session.path("id").asText());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody().isEmpty()).isTrue();
    }

    @Test
    void listsOnlyThisSessionsParticipantsInIdOrderWithoutExtraParticipantQueries() {
        var session = createSession(3);
        var sessionId = session.path("id").asText();
        var high = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        var low = UUID.fromString("11111111-1111-4111-8111-111111111111");
        jdbc.update("INSERT INTO participants (id, name, email) VALUES (?, ?, ?)", high, "High", "high@example.com");
        jdbc.update("INSERT INTO participants (id, name, email) VALUES (?, ?, ?)", low, "Low", "low@example.com");
        var highRegistration = register(sessionId, high.toString());
        var lowRegistration = register(sessionId, low.toString());
        var otherSession = createSession(1);
        register(otherSession.path("id").asText(), createParticipant().path("id").asText());

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        ResponseEntity<JsonNode> response;
        try {
            response = list(sessionId);
            // Session existence and one joined registration/participant query.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().size()).isEqualTo(2);
        assertParticipant(response.getBody().get(0), low.toString(), "Low", "low@example.com", lowRegistration);
        assertParticipant(response.getBody().get(1), high.toString(), "High", "high@example.com", highRegistration);
    }

    @Test
    void cancelsOnlyTheAddressedRegistrationAndPreservesOtherRelationshipsAndPeople() {
        var firstSession = createSession(2).path("id").asText();
        var secondSession = createSession(1).path("id").asText();
        var firstParticipant = createParticipant().path("id").asText();
        var secondParticipant = createParticipant().path("id").asText();
        var addressed = register(firstSession, firstParticipant);
        var sameSessionOtherRegistration = register(firstSession, secondParticipant);
        var otherSessionRegistration = register(secondSession, firstParticipant);

        assertNoContent(cancel(firstSession, addressed));

        assertThat(count("registrations", addressed)).isZero();
        assertThat(count("registrations", sameSessionOtherRegistration)).isEqualTo(1);
        assertThat(count("registrations", otherSessionRegistration)).isEqualTo(1);
        assertThat(count("participants", firstParticipant)).isEqualTo(1);
        assertThat(count("participants", secondParticipant)).isEqualTo(1);
        assertThat(count("sessions", firstSession)).isEqualTo(1);
        assertThat(count("sessions", secondSession)).isEqualTo(1);
        assertThat(list(firstSession).getBody().size()).isEqualTo(1);
    }

    @Test
    void cannotCancelARegistrationUnderAnotherSession() {
        var firstSession = createSession(1).path("id").asText();
        var secondSession = createSession(1).path("id").asText();
        var registrationId = register(firstSession, createParticipant().path("id").asText());

        assertError(cancel(secondSession, registrationId), HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND",
                "Registration was not found under this session.");
        assertThat(count("registrations", registrationId)).isEqualTo(1);
    }

    @Test
    void repeatedCancellationReturnsNotFound() {
        var sessionId = createSession(1).path("id").asText();
        var registrationId = register(sessionId, createParticipant().path("id").asText());
        assertNoContent(cancel(sessionId, registrationId));

        assertError(cancel(sessionId, registrationId), HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND",
                "Registration was not found under this session.");
        assertThat(list(sessionId).getBody().isEmpty()).isTrue();
    }

    @Test
    void cancellationAllowsTheSameParticipantToRegisterAgainWithANewId() {
        var sessionId = createSession(1).path("id").asText();
        var participantId = createParticipant().path("id").asText();
        var originalId = register(sessionId, participantId);
        assertNoContent(cancel(sessionId, originalId));

        var newId = register(sessionId, participantId);

        assertThat(newId).isNotEqualTo(originalId);
        assertThat(count("registrations", originalId)).isZero();
        assertThat(count("registrations", newId)).isEqualTo(1);
        assertThat(list(sessionId).getBody().size()).isEqualTo(1);
    }

    @Test
    void cancellationFreesCapacityForAnotherParticipant() {
        var sessionId = createSession(1).path("id").asText();
        var originalId = register(sessionId, createParticipant().path("id").asText());
        var nextParticipant = createParticipant().path("id").asText();
        var full = http.postForEntity("/api/sessions/{sessionId}/registrations",
                Map.of("participantId", nextParticipant), String.class, sessionId);
        assertError(full, HttpStatus.CONFLICT, "SESSION_FULL", "Session has reached its capacity.");
        assertNoContent(cancel(sessionId, originalId));

        var newId = register(sessionId, nextParticipant);

        assertThat(count("registrations", newId)).isEqualTo(1);
    }

    @Test
    void deletionIsBlockedUntilEveryRegistrationIsCancelled() {
        var session = createSession(2);
        var sessionId = session.path("id").asText();
        var participantId = createParticipant().path("id").asText();
        var first = register(sessionId, participantId);
        var second = register(sessionId, createParticipant().path("id").asText());
        assertBlocked(deleteSession(sessionId));
        assertNoContent(cancel(sessionId, first));
        assertBlocked(deleteSession(sessionId));
        assertNoContent(cancel(sessionId, second));

        assertNoContent(deleteSession(sessionId));

        assertThat(count("sessions", sessionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registrations", Long.class)).isZero();
        assertThat(count("participants", participantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participants", Long.class)).isEqualTo(2);
        assertThat(count("coaches", session.path("coachId").asText())).isEqualTo(1);
        assertError(listAsText(sessionId), HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session was not found.");
        assertError(deleteSession(sessionId), HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session was not found.");
    }

    @Test
    void deletesAnEmptySessionAndPreservesItsCoach() {
        var session = createSession(1);
        var sessionId = session.path("id").asText();

        assertNoContent(deleteSession(sessionId));

        assertThat(count("sessions", sessionId)).isZero();
        assertThat(count("coaches", session.path("coachId").asText())).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingResources")
    void returnsDocumentedMissingResourceErrors(HttpMethod method, String path, String code, String message) {
        assertError(http.exchange(path, method, HttpEntity.EMPTY, String.class), HttpStatus.NOT_FOUND, code, message);
    }

    @Test
    void aMissingRegistrationUnderAnExistingSessionReturnsNotFound() {
        var sessionId = createSession(1).path("id").asText();
        assertError(cancel(sessionId, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND",
                "Registration was not found under this session.");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("invalidUuids")
    void invalidPathUuidsReturnSafeBadRequests(HttpMethod method, String path) {
        assertError(http.exchange(path, method, HttpEntity.EMPTY, String.class), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST", "Bad Request");
    }

    @Test
    void theActualForeignKeyProtectsAgainstAStaleRegistrationCheckAndRollsBackDeletion() {
        var sessionId = createSession(1).path("id").asText();
        var participantId = createParticipant().path("id").asText();
        var registrationId = register(sessionId, participantId);
        // Only the pre-check is stale. The delete executes against the real PostgreSQL FK.
        doReturn(false).when(registrations).existsBySessionId(UUID.fromString(sessionId));

        assertBlocked(deleteSession(sessionId));

        assertThat(count("sessions", sessionId)).isEqualTo(1);
        assertThat(count("registrations", registrationId)).isEqualTo(1);
        assertThat(count("participants", participantId)).isEqualTo(1);
        assertNoContent(cancel(sessionId, registrationId));
        assertNoContent(deleteSession(sessionId));
    }

    @Test
    void unexpectedPersistenceFailuresRemainSanitizedInternalErrors() {
        var sessionId = createSession(1).path("id").asText();
        var registrationId = register(sessionId, createParticipant().path("id").asText());
        doThrow(new DataIntegrityViolationException("private database details"))
                .when(registrations).deleteBySessionAndRegistrationId(UUID.fromString(sessionId), UUID.fromString(registrationId));

        assertError(cancel(sessionId, registrationId), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "An unexpected error occurred.");
        assertThat(count("registrations", registrationId)).isEqualTo(1);
    }

    @Test
    void swaggerDocumentsTheArraySchemaAndBodylessDeletionResponses() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        var paths = response.getBody().path("paths");
        var list = paths.path("/api/sessions/{sessionId}/participants").path("get");
        assertThat(list.path("operationId").asText()).isEqualTo("listSessionParticipants");
        assertThat(list.path("responses").fieldNames()).toIterable().containsExactlyInAnyOrder("200", "400", "404", "500");
        var schema = list.path("responses").path("200").path("content").path("application/json").path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("array");
        assertThat(schema.path("items").path("$ref").asText()).isEqualTo("#/components/schemas/RegisteredParticipantResponse");
        var cancellation = paths.path("/api/sessions/{sessionId}/registrations/{registrationId}").path("delete");
        assertThat(cancellation.path("operationId").asText()).isEqualTo("cancelRegistration");
        assertThat(cancellation.path("responses").fieldNames()).toIterable().containsExactlyInAnyOrder("204", "400", "404", "500");
        var deletion = paths.path("/api/sessions/{sessionId}").path("delete");
        assertThat(deletion.path("operationId").asText()).isEqualTo("deleteSession");
        assertThat(deletion.path("responses").fieldNames()).toIterable().containsExactlyInAnyOrder("204", "400", "404", "409", "500");
        for (var operation : List.of(cancellation, deletion)) {
            assertThat(operation.path("responses").path("204").has("content")).isFalse();
        }
    }

    private JsonNode createSession(int capacity) {
        var coach = http.postForEntity("/api/coaches", Map.of("name", "Alex", "email", "alex@example.com"), JsonNode.class);
        assertThat(coach.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(coach.getBody()).isNotNull();
        var response = http.postForEntity("/api/sessions", Map.of("coachId", coach.getBody().path("id").asText(),
                "startTime", "2026-10-05T14:00:00Z", "endTime", "2026-10-05T15:00:00Z",
                "capacity", capacity, "location", "Court A"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private JsonNode createParticipant() {
        var response = http.postForEntity("/api/participants", Map.of("name", "Sam", "email", "sam@example.com"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private String register(String sessionId, String participantId) {
        var response = http.postForEntity("/api/sessions/{sessionId}/registrations", Map.of("participantId", participantId),
                JsonNode.class, sessionId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("id").asText();
    }

    private ResponseEntity<JsonNode> list(String sessionId) {
        return http.getForEntity("/api/sessions/{sessionId}/participants", JsonNode.class, sessionId);
    }

    private ResponseEntity<String> listAsText(String sessionId) {
        return http.getForEntity("/api/sessions/{sessionId}/participants", String.class, sessionId);
    }

    private ResponseEntity<String> cancel(String sessionId, String registrationId) {
        return http.exchange("/api/sessions/{sessionId}/registrations/{registrationId}", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class, sessionId, registrationId);
    }

    private ResponseEntity<String> deleteSession(String sessionId) {
        return http.exchange("/api/sessions/{sessionId}", HttpMethod.DELETE, HttpEntity.EMPTY, String.class, sessionId);
    }

    private long count(String table, String id) {
        // Table names come only from this test's literals; values remain bound parameters.
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, UUID.fromString(id));
    }

    private void assertParticipant(JsonNode body, String id, String name, String email, String registrationId) {
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("id").asText()).isEqualTo(id);
        assertThat(body.path("name").asText()).isEqualTo(name);
        assertThat(body.path("email").asText()).isEqualTo(email);
        assertThat(body.path("registrationId").asText()).isEqualTo(registrationId);
    }

    private void assertNoContent(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    private void assertBlocked(ResponseEntity<String> response) {
        assertError(response, HttpStatus.CONFLICT, "SESSION_HAS_REGISTRATIONS",
                "Cancel all registrations before deleting this session.");
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        // Parsing through Jackson also ensures errors remain JSON, with no internal fields.
        JsonNode body;
        try {
            body = mapper.readTree(response.getBody());
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Expected JSON error", exception);
        }
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isEqualTo(message);
    }

    private static Stream<Arguments> missingResources() {
        var session = UUID.randomUUID();
        var registration = UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/sessions/" + session + "/participants", "SESSION_NOT_FOUND", "Session was not found."),
                Arguments.of(HttpMethod.DELETE, "/api/sessions/" + session, "SESSION_NOT_FOUND", "Session was not found."),
                Arguments.of(HttpMethod.DELETE, "/api/sessions/" + session + "/registrations/" + registration,
                        "SESSION_NOT_FOUND", "Session was not found."));
    }

    private static Stream<Arguments> invalidUuids() {
        var id = UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/sessions/invalid/participants"),
                Arguments.of(HttpMethod.DELETE, "/api/sessions/invalid"),
                Arguments.of(HttpMethod.DELETE, "/api/sessions/invalid/registrations/" + id),
                Arguments.of(HttpMethod.DELETE, "/api/sessions/" + id + "/registrations/invalid"));
    }
}
