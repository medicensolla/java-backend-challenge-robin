package com.example.jbc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.example.jbc.coaches.Coach;
import com.example.jbc.coaches.CoachRepository;
import com.example.jbc.participants.Participant;
import com.example.jbc.participants.ParticipantRepository;
import com.example.jbc.registrations.Registration;
import com.example.jbc.registrations.RegistrationRepository;
import com.example.jbc.sessions.SessionRepository;
import com.example.jbc.sessions.TrainingSession;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIT {

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
    private EntityManager entityManager;

    @Autowired
    private CoachRepository coaches;

    @Autowired
    private ParticipantRepository participants;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private RegistrationRepository registrations;

    @Test
    void startsHttpAndPersistenceAgainstPostgresql() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("openapi").asText()).startsWith("3.");
        for (var endpoint : new String[]{"/api/coaches", "/api/participants"}) {
            var documentedResponses = response.getBody().path("paths").path(endpoint).path("post").path("responses");
            assertThat(documentedResponses.has("201")).isTrue();
            assertThat(documentedResponses.has("400")).isTrue();
            assertThat(documentedResponses.has("500")).isTrue();
        }
        var health = http.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo(postgres.getDatabaseName());
        assertThat(jdbc.queryForObject("select current_setting('TimeZone')", String.class))
                .isEqualTo("UTC");
        assertThat(entityManagerFactory.isOpen()).isTrue();
    }

    @Test
    @Transactional
    void persistsAndReloadsARegistrationWithItsSessionCoachAndParticipant() {
        var coach = coaches.save(new Coach("Alex Rivera", "alex@example.com"));
        var participant = participants.save(new Participant("Sam Lee", "sam@example.com"));
        var start = Instant.parse("2026-10-05T14:00:00.123456Z");
        var end = start.plusSeconds(3600);
        var session = sessions.save(new TrainingSession(coach, start, end, 10, "Court A"));
        var registration = registrations.save(new Registration(session, participant));

        entityManager.flush();
        entityManager.clear();

        assertThat(coach.getId()).isNotNull();
        assertThat(participant.getId()).isNotNull();
        assertThat(session.getId()).isNotNull();
        assertThat(registration.getId()).isNotNull();
        var stored = registrations.findById(registration.getId()).orElseThrow();
        assertThat(stored.getSession().getId()).isEqualTo(session.getId());
        assertThat(stored.getSession().getStartTime()).isEqualTo(start);
        assertThat(stored.getSession().getEndTime()).isEqualTo(end);
        assertThat(stored.getSession().getCapacity()).isEqualTo(10);
        assertThat(stored.getSession().getLocation()).isEqualTo("Court A");
        assertThat(stored.getSession().getCoach().getId()).isEqualTo(coach.getId());
        assertThat(stored.getSession().getCoach().getName()).isEqualTo("Alex Rivera");
        assertThat(stored.getSession().getCoach().getEmail()).isEqualTo("alex@example.com");
        assertThat(stored.getParticipant().getId()).isEqualTo(participant.getId());
        assertThat(stored.getParticipant().getName()).isEqualTo("Sam Lee");
        assertThat(stored.getParticipant().getEmail()).isEqualTo("sam@example.com");
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/coaches", "/api/participants"})
    void createsPeopleWithIdsAndAllowsRepeatedEmails(String endpoint) {
        var payload = Map.of("name", "Alex Rivera", "email", "alex@example.com");
        var response = http.postForEntity(endpoint, payload, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(3);
        var id = UUID.fromString(body.path("id").asText());
        assertThat(body.path("name").asText()).isEqualTo(payload.get("name"));
        assertThat(body.path("email").asText()).isEqualTo(payload.get("email"));
        var table = endpoint.substring("/api/".length());
        assertThat(jdbc.queryForObject("SELECT name FROM " + table + " WHERE id = ?", String.class, id))
                .isEqualTo(payload.get("name"));
        assertThat(jdbc.queryForObject("SELECT email FROM " + table + " WHERE id = ?", String.class, id))
                .isEqualTo(payload.get("email"));

        var repeatedEmail = http.postForEntity(endpoint, payload, JsonNode.class);
        assertThat(repeatedEmail.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeatedEmail.getBody()).isNotNull();
        assertThat(UUID.fromString(repeatedEmail.getBody().path("id").asText())).isNotEqualTo(id);
    }

    @ParameterizedTest(name = "{0} rejects invalid {2}")
    @MethodSource("invalidPeople")
    void rejectsInvalidPeopleWithoutPersistingThem(String endpoint, Map<String, String> payload, String field) {
        var table = endpoint.substring("/api/".length());
        var countBefore = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);

        var response = http.postForEntity(endpoint, payload, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("fieldErrors").findValuesAsText("field")).contains(field);
        assertThat(body.has("trace")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isEqualTo(countBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/coaches", "/api/participants"})
    void rejectsMalformedJsonWithASafeError(String endpoint) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>("{\"name\":", headers);

        var response = http.postForEntity(endpoint, request, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().path("message").asText()).isEqualTo("Request body must contain valid JSON.");
        assertThat(response.getBody().size()).isEqualTo(2);
    }

    private static Stream<Arguments> invalidPeople() {
        return Stream.of("/api/coaches", "/api/participants").flatMap(endpoint -> Stream.of(
                Arguments.of(endpoint, Map.of("name", "  ", "email", "alex@example.com"), "name"),
                Arguments.of(endpoint, Map.of("name", "Alex", "email", "  "), "email"),
                Arguments.of(endpoint, Map.of("name", "Alex", "email", "not-an-email"), "email"),
                Arguments.of(endpoint, Map.of(), "name")));
    }

}
