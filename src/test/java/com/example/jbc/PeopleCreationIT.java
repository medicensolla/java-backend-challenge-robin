package com.example.jbc;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeopleCreationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearFixture() {
        jdbc.update("DELETE FROM registrations");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM participants");
        jdbc.update("DELETE FROM coaches");
    }

    @ParameterizedTest(name = "{0}: duplicate name {1}, email {2}")
    @MethodSource("duplicateCombinations")
    void rejectsExactAndNormalizedDuplicatesWithoutChangingTheOriginal(
            String endpoint, String name, String email) {
        var suppliedName = "  José\t Rivera\n Stone  ";
        var original = create(endpoint, suppliedName, "Alex@Example.com");
        var table = endpoint.substring("/api/".length());
        var stored = jdbc.queryForMap("SELECT id, name, email FROM " + table);

        assertDuplicate(post(endpoint, name, email), endpoint);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT id, name, email FROM " + table)).isEqualTo(stored);
        assertThat(original.path("name").asText())
                .isEqualTo(endpoint.equals("/api/coaches") ? "José Rivera Stone" : suppliedName);
        assertThat(original.path("email").asText()).isEqualTo("Alex@Example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/coaches", "/api/participants"})
    void allowsDifferentNamesDifferentEmailsAndDistinctAccents(String endpoint) {
        var original = create(endpoint, "José Rivera", "alex@example.com");
        var sameEmail = create(endpoint, "Taylor Kim", "alex@example.com");
        var sameName = create(endpoint, "José Rivera", "other@example.com");
        var distinctAccent = create(endpoint, "Jose Rivera", "alex@example.com");

        assertThat(Stream.of(original, sameEmail, sameName, distinctAccent)
                .map(person -> person.path("id").asText()).toList()).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + endpoint.substring("/api/".length()), Long.class))
                .isEqualTo(4);
    }

    @Test
    void allowsTheSameCombinationInBothRoles() {
        var coach = create("/api/coaches", "Alex Rivera", "alex@example.com");
        var participant = create("/api/participants", "Alex Rivera", "alex@example.com");

        assertThat(coach.path("id").asText()).isNotEqualTo(participant.path("id").asText());
        assertDuplicate(post("/api/coaches", "alex rivera", "Alex@example.com"), "/api/coaches");
        assertDuplicate(post("/api/participants", "alex rivera", "Alex@example.com"), "/api/participants");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coaches", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participants", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/coaches", "/api/participants"})
    void preservesLegacyDuplicatesAndRejectsNewMatchesIncludingTrimmedEmails(String endpoint) {
        var table = endpoint.substring("/api/".length());
        jdbc.update("INSERT INTO " + table + " (id, name, email) VALUES (?, ?, ?)",
                UUID.randomUUID(), "  Alex\tRivera\n", " \tAlex@Example.com\r\n\f\u000b ");
        assertDuplicate(post(endpoint, "ALEX  RIVERA", "alex@example.com"), endpoint);
        jdbc.update("INSERT INTO " + table + " (id, name, email) VALUES (?, ?, ?)",
                UUID.randomUUID(), "alex rivera", "alex@example.com");
        var stored = jdbc.queryForList("SELECT id, name, email FROM " + table + " ORDER BY id");

        assertDuplicate(post(endpoint, "ALEX  RIVERA", "Alex@example.com"), endpoint);

        assertThat(jdbc.queryForList("SELECT id, name, email FROM " + table + " ORDER BY id")).isEqualTo(stored);
        assertThat(stored).hasSize(2);
    }

    private JsonNode create(String endpoint, String name, String email) {
        var response = post(endpoint, name, email);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "email");
        assertThat(UUID.fromString(body.path("id").asText())).isNotNull();
        return body;
    }

    private ResponseEntity<JsonNode> post(String endpoint, String name, String email) {
        return http.postForEntity(endpoint, Map.of("name", name, "email", email), JsonNode.class);
    }

    private void assertDuplicate(ResponseEntity<JsonNode> response, String endpoint) {
        var coach = endpoint.equals("/api/coaches");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("title").asText()).isEqualTo("Conflict");
        assertThat(body.path("status").asInt()).isEqualTo(409);
        assertThat(body.path("instance").asText()).isEqualTo(endpoint);
        assertThat(body.path("code").asText()).isEqualTo(coach ? "DUPLICATE_COACH" : "DUPLICATE_PARTICIPANT");
        assertThat(body.path("detail").asText()).isEqualTo(
                (coach ? "Coach" : "Participant") + " with this name and email already exists.");
    }

    private static Stream<Arguments> duplicateCombinations() {
        return Stream.of("/api/coaches", "/api/participants").flatMap(endpoint -> Stream.of(
                Arguments.of(endpoint, "  José\t Rivera\n Stone  ", "Alex@Example.com"),
                Arguments.of(endpoint, "José Rivera Stone", "Alex@Example.com"),
                Arguments.of(endpoint, "josé rivera stone", "alex@example.com"),
                Arguments.of(endpoint, "\tJOSÉ\fRivera\u000bStone\r\n", "Alex@example.com")));
    }
}
