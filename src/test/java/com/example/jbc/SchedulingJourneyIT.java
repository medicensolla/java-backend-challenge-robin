package com.example.jbc;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingJourneyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void clearThisContainersData() {
        jdbc.update("DELETE FROM registrations");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM participants");
        jdbc.update("DELETE FROM coaches");
    }

    @Test
    void completesSchedulingFilteringRegistrationCancellationAndDeletionThroughHttp() {
        var coach = createPerson("/api/coaches", "Alex Rivera", "alex@example.com");
        var firstParticipant = createPerson("/api/participants", "Sam Lee", "sam@example.com");
        var secondParticipant = createPerson("/api/participants", "Robin Kim", "robin@example.com");
        var waitingParticipant = createPerson("/api/participants", "Jo Diaz", "jo@example.com");
        var coachId = coach.path("id").asText();
        var session = createSession(coachId, 2, "2026-10-05T10:00:00-04:00", "2026-10-05T11:00:00-04:00");
        var sessionId = session.path("id").asText();
        assertThat(session.path("coachId").asText()).isEqualTo(coachId);
        assertThat(session.path("startTime").asText()).isEqualTo("2026-10-05T14:00:00Z");
        assertThat(session.path("endTime").asText()).isEqualTo("2026-10-05T15:00:00Z");
        assertThat(session.path("capacity").asInt()).isEqualTo(2);
        assertThat(session.path("location").asText()).isEqualTo("Court A");
        // Nonmatching sessions establish that coach and interval filters work together.
        createSession(coachId, 1, "2026-10-05T15:00:00Z", "2026-10-05T16:00:00Z");
        var otherCoach = createPerson("/api/coaches", "Pat Stone", "pat@example.com");
        createSession(otherCoach.path("id").asText(), 1, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");
        var filters = Map.of("coachId", coachId, "from", "2026-10-05T16:15:00+02:00", "to", "2026-10-05T10:45:00-04:00");
        var schedule = listSessions(filters);
        assertThat(schedule.size()).isEqualTo(1);
        assertThat(schedule.get(0)).isEqualTo(session);
        assertThat(listParticipants(sessionId).isEmpty()).isTrue();

        var first = register(sessionId, firstParticipant.path("id").asText());
        var second = register(sessionId, secondParticipant.path("id").asText());
        var expected = mapper.createArrayNode()
                .add(registeredParticipant(firstParticipant, first))
                .add(registeredParticipant(secondParticipant, second));
        assertThat(listParticipants(sessionId).elements()).toIterable().containsExactlyInAnyOrderElementsOf(expected);

        cancel(sessionId, first.path("id").asText());
        var replacement = register(sessionId, waitingParticipant.path("id").asText());
        var afterCancellation = mapper.createArrayNode()
                .add(registeredParticipant(secondParticipant, second))
                .add(registeredParticipant(waitingParticipant, replacement));
        assertThat(listParticipants(sessionId).elements()).toIterable().containsExactlyInAnyOrderElementsOf(afterCancellation);
        cancel(sessionId, second.path("id").asText());
        cancel(sessionId, replacement.path("id").asText());
        assertThat(listParticipants(sessionId).isEmpty()).isTrue();
        assertNoContent(exchange(HttpMethod.DELETE, "/api/sessions/" + sessionId, null));

        assertThat(listSessions(filters).isEmpty()).isTrue();
        assertError(exchange(HttpMethod.GET, "/api/sessions/" + sessionId + "/participants", null),
                HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session was not found.");
    }

    @Test
    void conflictsLeaveTheExistingScheduleAndRegistrationsAvailable() {
        var coach = createPerson("/api/coaches", "Alex Rivera", "alex@example.com");
        var firstParticipant = createPerson("/api/participants", "Sam Lee", "sam@example.com");
        var secondParticipant = createPerson("/api/participants", "Robin Kim", "robin@example.com");
        var waitingParticipant = createPerson("/api/participants", "Jo Diaz", "jo@example.com");
        var coachId = coach.path("id").asText();
        var session = createSession(coachId, 2, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");
        var sessionId = session.path("id").asText();
        assertError(exchange(HttpMethod.POST, "/api/sessions",
                        sessionRequest(coachId, 2, "2026-10-05T14:30:00Z", "2026-10-05T15:30:00Z")),
                HttpStatus.CONFLICT, "COACH_OVERLAP", "Coach already has a session overlapping this interval.");

        var firstId = firstParticipant.path("id").asText();
        var first = register(sessionId, firstId);
        assertDuplicate(sessionId, firstId);
        var second = register(sessionId, secondParticipant.path("id").asText());
        assertDuplicate(sessionId, firstId);
        assertError(exchange(HttpMethod.POST, "/api/sessions/" + sessionId + "/registrations",
                        Map.of("participantId", waitingParticipant.path("id").asText())),
                HttpStatus.CONFLICT, "SESSION_FULL", "Session has reached its capacity.");
        assertError(exchange(HttpMethod.DELETE, "/api/sessions/" + sessionId, null),
                HttpStatus.CONFLICT, "SESSION_HAS_REGISTRATIONS", "Cancel all registrations before deleting this session.");

        var schedule = listSessions(Map.of("coachId", coachId));
        assertThat(schedule.size()).isEqualTo(1);
        assertThat(schedule.get(0)).isEqualTo(session);
        var expected = mapper.createArrayNode()
                .add(registeredParticipant(firstParticipant, first))
                .add(registeredParticipant(secondParticipant, second));
        assertThat(listParticipants(sessionId).elements()).toIterable().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void invalidInputsAndMissingReferencesDoNotAlterAValidRegistration() {
        var coach = createPerson("/api/coaches", "Alex Rivera", "alex@example.com");
        var participant = createPerson("/api/participants", "Sam Lee", "sam@example.com");
        var coachId = coach.path("id").asText();
        var session = createSession(coachId, 2, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");
        var sessionId = session.path("id").asText();
        var registration = register(sessionId, participant.path("id").asText());

        assertError(exchange(HttpMethod.POST, "/api/sessions",
                        sessionRequest(coachId, 2, "2026-10-05T16:00:00Z", "2026-10-05T15:00:00Z")),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "startTime must be before endTime at microsecond precision.");
        assertError(exchange(HttpMethod.GET, "/api/sessions/not-a-uuid/participants", null),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Bad Request");
        assertError(exchange(HttpMethod.POST, "/api/sessions/" + sessionId + "/registrations",
                        Map.of("participantId", UUID.randomUUID().toString())),
                HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Participant was not found.");
        assertError(exchange(HttpMethod.DELETE,
                        "/api/sessions/" + sessionId + "/registrations/" + UUID.randomUUID(), null),
                HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "Registration was not found under this session.");

        var schedule = listSessions(Map.of("coachId", coachId));
        assertThat(schedule.size()).isEqualTo(1);
        assertThat(schedule.get(0)).isEqualTo(session);
        assertThat(listParticipants(sessionId).elements()).toIterable().containsExactly(registeredParticipant(participant, registration));
    }

    private JsonNode createPerson(String endpoint, String name, String email) {
        var person = create(endpoint, Map.of("name", name, "email", email));
        assertThat(person.path("name").asText()).isEqualTo(name);
        assertThat(person.path("email").asText()).isEqualTo(email);
        return person;
    }

    private JsonNode createSession(String coachId, int capacity, String start, String end) {
        return create("/api/sessions", sessionRequest(coachId, capacity, start, end));
    }

    private Map<String, Object> sessionRequest(String coachId, int capacity, String start, String end) {
        return Map.of("coachId", coachId, "startTime", start, "endTime", end, "capacity", capacity, "location", "Court A");
    }

    private JsonNode create(String path, Map<String, ?> request) {
        var created = assertJsonResponse(exchange(HttpMethod.POST, path, request), HttpStatus.CREATED);
        assertThat(UUID.fromString(created.path("id").asText())).isNotNull();
        return created;
    }

    private JsonNode register(String sessionId, String participantId) {
        var registration = create("/api/sessions/" + sessionId + "/registrations", Map.of("participantId", participantId));
        assertThat(registration.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(registration.path("participantId").asText()).isEqualTo(participantId);
        assertThat(registration.path("id").asText()).isNotIn(sessionId, participantId);
        return registration;
    }

    private JsonNode registeredParticipant(JsonNode participant, JsonNode registration) {
        return mapper.createObjectNode()
                .put("id", participant.path("id").asText())
                .put("name", participant.path("name").asText())
                .put("email", participant.path("email").asText())
                .put("registrationId", registration.path("id").asText());
    }

    private JsonNode listSessions(Map<String, String> filters) {
        var uri = UriComponentsBuilder.fromUriString(http.getRootUri() + "/api/sessions");
        filters.forEach((name, value) -> uri.queryParam(name, "{" + name + "}"));
        // Encode URI variables strictly, preserving the '+' in positive UTC offsets.
        var response = http.getForEntity(uri.encode().buildAndExpand(filters).toUri(), String.class);
        var sessions = assertJsonResponse(response, HttpStatus.OK).path("content");
        assertThat(sessions.isArray()).isTrue();
        return sessions;
    }

    private JsonNode listParticipants(String sessionId) {
        var participants = assertJsonResponse(exchange(HttpMethod.GET, "/api/sessions/" + sessionId + "/participants", null),
                HttpStatus.OK).path("content");
        assertThat(participants.isArray()).isTrue();
        return participants;
    }

    private void cancel(String sessionId, String registrationId) {
        assertNoContent(exchange(HttpMethod.DELETE,
                "/api/sessions/" + sessionId + "/registrations/" + registrationId, null));
    }

    private void assertDuplicate(String sessionId, String participantId) {
        assertError(exchange(HttpMethod.POST, "/api/sessions/" + sessionId + "/registrations",
                        Map.of("participantId", participantId)),
                HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION", "Participant is already registered for this session.");
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body), String.class);
    }

    private JsonNode assertJsonResponse(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(status.isError() ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).isNotBlank();
        try {
            return mapper.readTree(response.getBody());
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Expected a JSON response", exception);
        }
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code, String message) {
        var error = assertJsonResponse(response, status);
        assertThat(error.size()).isEqualTo(6);
        assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(error.fieldNames()).toIterable().containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertThat(error.path("type").asText()).isEqualTo("about:blank");
        assertThat(error.path("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(error.path("status").asInt()).isEqualTo(status.value());
        assertThat(error.path("instance").asText()).startsWith("/api/").doesNotContain("?");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("detail").asText()).isEqualTo(message);
    }

    private void assertNoContent(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
