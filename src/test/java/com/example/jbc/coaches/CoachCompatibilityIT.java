package com.example.jbc.coaches;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoachCompatibilityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CoachRepository coaches;

    @BeforeEach
    void clearFixture() {
        jdbc.update("DELETE FROM registrations");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM participants");
        jdbc.update("DELETE FROM coaches");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', emptyValue = "", textBlock = """
            '  Alex   Rivera Stone  ' | Alex | Rivera Stone | Alex Rivera Stone
            '  Prince  ' | Prince | '' | Prince
            María del Río | María | del Río | María del Río
            """)
    void apiWritesSplitNamesAndRetainsThePublicResponse(String input, String first, String last, String name) {
        var response = http.postForEntity("/api/coaches", Map.of("name", input, "email", "alex@example.com"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().size()).isEqualTo(3);
        var id = UUID.fromString(response.getBody().path("id").asText());
        assertThat(response.getBody().path("name").asText()).isEqualTo(name);
        assertThat(response.getBody().path("email").asText()).isEqualTo("alex@example.com");
        assertRepresentations(id, first, last, name, "alex@example.com");
        createSessionWithCoach(id);
    }

    @Test
    void legacyWritesRemainReadableByJpaAndUsableForScheduling() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)", id, "  Alex\tRivera\n Stone  ", "old@example.com");
        assertRepresentations(id, "Alex", "Rivera Stone", "Alex Rivera Stone", "old@example.com");

        jdbc.update("UPDATE coaches SET name = ? WHERE id = ?", "\tPrince\r\n", id);
        assertRepresentations(id, "Prince", "", "Prince", "old@example.com");
        createSessionWithCoach(id);
        assertThat(jdbc.queryForObject("SELECT coach_id FROM sessions", UUID.class)).isEqualTo(id);
    }

    @Test
    void splitWritesRemainReadableToLegacyReadersAndPreserveExistingSessions() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, first_name, last_name, email) VALUES (?, ?, ?, ?)",
                id, " Alex ", " Rivera\tStone ", "new@example.com");
        assertRepresentations(id, "Alex", "Rivera Stone", "Alex Rivera Stone", "new@example.com");
        createSessionWithCoach(id);

        jdbc.update("UPDATE coaches SET first_name = ?, last_name = ? WHERE id = ?", " Robin ", "", id);
        assertRepresentations(id, "Robin", "", "Robin", "new@example.com");
        assertThat(jdbc.queryForObject("SELECT coach_id FROM sessions", UUID.class)).isEqualTo(id);
        jdbc.update("UPDATE coaches SET last_name = ? WHERE id = ?", " Kim\n Stone ", id);
        assertRepresentations(id, "Robin", "Kim Stone", "Robin Kim Stone", "new@example.com");
    }

    @Test
    void emailOnlyUpdatesDoNotChangeEitherNameRepresentation() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)", id, "Alex Rivera", "old@example.com");

        jdbc.update("UPDATE coaches SET email = ? WHERE id = ?", "new@example.com", id);

        assertRepresentations(id, "Alex", "Rivera", "Alex Rivera", "new@example.com");
    }

    @Test
    void acceptsBothRepresentationsWhenTheyAgreeAfterNormalization() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, first_name, last_name, email) VALUES (?, ?, ?, ?, ?)",
                id, "  Alex\tRivera  ", " Alex ", " Rivera ", "alex@example.com");
        assertRepresentations(id, "Alex", "Rivera", "Alex Rivera", "alex@example.com");

        jdbc.update("UPDATE coaches SET name = ?, first_name = ?, last_name = ? WHERE id = ?",
                " Robin\nKim ", " Robin ", " Kim ", id);
        assertRepresentations(id, "Robin", "Kim", "Robin Kim", "alex@example.com");
    }

    @Test
    void rejectsInconsistentRepresentationsOnInsertAndUpdateWithoutChangingTheCoach() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO coaches (id, name, first_name, last_name, email) VALUES (?, ?, ?, ?, ?)",
                id, "Alex Rivera", "Robin", "Kim", "alex@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(coaches.existsById(id)).isFalse();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)", id, "Alex Rivera", "alex@example.com");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE coaches SET name = ?, first_name = ?, last_name = ?, email = ? WHERE id = ?",
                "Sam Lee", "Robin", "Kim", "changed@example.com", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertRepresentations(id, "Alex", "Rivera", "Alex Rivera", "alex@example.com");
    }

    @ParameterizedTest
    @CsvSource({"9", "10", "11", "12", "13"})
    void javaAndLegacySqlNormalizeTheSameAsciiWhitespace(int character) {
        var whitespace = String.valueOf((char) character);
        var input = whitespace + "Alex" + whitespace + " Rivera " + whitespace;
        var javaCoach = new Coach(input, "alex@example.com");
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)", id, input, "alex@example.com");

        assertRepresentations(id, javaCoach.getFirstName(), javaCoach.getLastName(), javaCoach.getName(), javaCoach.getEmail());
    }

    @Test
    void swaggerDocumentsNormalizationWithoutExposingSplitFields() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        var operation = response.getBody().path("paths").path("/api/coaches").path("post");
        assertThat(operation.path("description").asText()).contains("Normalize");
        var schemas = response.getBody().path("components").path("schemas");
        assertThat(schemas.path("CoachResponse").path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "email");
        assertThat(schemas.path("CreateCoachRequest").path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("name", "email");
    }

    private void assertRepresentations(UUID id, String first, String last, String name, String email) {
        // Each repository call loads a fresh entity after the completed HTTP/SQL transaction.
        var coach = coaches.findById(id).orElseThrow();
        assertThat(coach.getId()).isEqualTo(id);
        assertThat(coach.getFirstName()).isEqualTo(first);
        assertThat(coach.getLastName()).isEqualTo(last);
        assertThat(coach.getName()).isEqualTo(name);
        assertThat(coach.getEmail()).isEqualTo(email);
        var stored = jdbc.queryForMap("SELECT name, first_name, last_name, email FROM coaches WHERE id = ?", id);
        assertThat(stored).containsExactlyInAnyOrderEntriesOf(Map.of("name", name, "first_name", first, "last_name", last, "email", email));
    }

    private void createSessionWithCoach(UUID coachId) {
        var response = http.postForEntity("/api/sessions", Map.of("coachId", coachId,
                "startTime", "2026-10-05T14:00:00Z", "endTime", "2026-10-05T15:00:00Z", "capacity", 2, "location", "Court A"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("coachId").asText()).isEqualTo(coachId.toString());
    }
}
