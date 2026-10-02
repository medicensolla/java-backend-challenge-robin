package com.example.jbc.sessions;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.example.jbc.coaches.CoachRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCreationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CoachRepository coaches;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void createsASessionWithUtcTimestampsAndPersistsTheReturnedValues() {
        var coachId = createCoach();
        var payload = payload(coachId, "2020-10-05T10:00:00.123456789-04:00", "2020-10-05T17:00:00.987654321+02:00");

        var response = http.postForEntity("/api/sessions", payload, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(6);
        var id = UUID.fromString(body.path("id").asText());
        assertThat(body.path("coachId").asText()).isEqualTo(coachId.toString());
        assertThat(body.path("startTime").asText()).isEqualTo("2020-10-05T14:00:00.123456Z");
        assertThat(body.path("endTime").asText()).isEqualTo("2020-10-05T15:00:00.987654Z");
        assertThat(body.path("capacity").asInt()).isEqualTo(10);
        assertThat(body.path("location").asText()).isEqualTo("Court A");
        var stored = jdbc.queryForMap("SELECT coach_id, start_time, end_time, capacity, location FROM sessions WHERE id = ?", id);
        assertThat(stored.get("coach_id")).isEqualTo(coachId);
        assertThat(((java.sql.Timestamp) stored.get("start_time")).toInstant())
                .isEqualTo(Instant.parse(body.path("startTime").asText()));
        assertThat(((java.sql.Timestamp) stored.get("end_time")).toInstant())
                .isEqualTo(Instant.parse(body.path("endTime").asText()));
        assertThat(stored.get("capacity")).isEqualTo(10);
        assertThat(stored.get("location")).isEqualTo("Court A");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windows")
    void enforcesHalfOpenOverlapBoundaries(String description, String start, String end, boolean overlap) {
        var coachId = createCoach();
        var existing = http.postForEntity("/api/sessions", payload(coachId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z"), JsonNode.class);
        assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var response = http.postForEntity("/api/sessions", payload(coachId, start, end), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(overlap ? HttpStatus.CONFLICT : HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        if (overlap) {
            assertThat(response.getBody().path("code").asText()).isEqualTo("COACH_OVERLAP");
            assertThat(response.getBody().path("detail").asText()).isEqualTo("Coach already has a session overlapping this interval.");
            assertThat(response.getBody().size()).isEqualTo(6);
        } else {
            assertThat(UUID.fromString(response.getBody().path("id").asText())).isNotNull();
        }
        assertThat(sessionCount(coachId)).isEqualTo(overlap ? 1 : 2);
    }

    @Test
    void allowsTheSameWindowForDifferentCoaches() {
        for (var coachId : List.of(createCoach(), createCoach())) {
            var response = http.postForEntity("/api/sessions", payload(coachId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z"), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(sessionCount(coachId)).isEqualTo(1);
        }
    }

    @Test
    void returnsNotFoundForAMissingCoach() {
        var coachId = UUID.randomUUID();
        var response = http.postForEntity("/api/sessions", payload(coachId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("COACH_NOT_FOUND");
        assertThat(response.getBody().path("detail").asText()).isEqualTo("Coach was not found.");
        assertThat(response.getBody().size()).isEqualTo(6);
        assertThat(sessionCount(coachId)).isZero();
    }

    @ParameterizedTest(name = "invalid {0}: {1}")
    @MethodSource("invalidFields")
    void rejectsInvalidInputBeforeCoachLookup(String field, Object value) {
        var coachId = UUID.randomUUID();
        var payload = new HashMap<>(payload(coachId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z"));
        payload.put(field, value);

        var response = http.postForEntity("/api/sessions", payload, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().path("detail").asText()).isNotBlank();
        assertThat(response.getBody().has("trace")).isFalse();
        assertThat(sessionCount(coachId)).isZero();
    }

    @Test
    void rejectsAnEmptyRequestWithFieldErrors() {
        var response = http.postForEntity("/api/sessions", Map.of(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("fieldErrors").findValuesAsText("field"))
                .containsExactly("capacity", "coachId", "endTime", "location", "startTime");
    }

    @Test
    void concurrentConflictingRequestsOnAnEmptyScheduleCreateExactlyOneSession() throws Exception {
        var coachId = createCoach();
        var payload = payload(coachId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = new TransactionTemplate(transactionManager).execute(status -> {
                coaches.findByIdForUpdate(coachId).orElseThrow();
                var first = executor.submit(() -> http.postForEntity("/api/sessions", payload, JsonNode.class));
                var second = executor.submit(() -> http.postForEntity("/api/sessions", payload, JsonNode.class));
                // Prove both HTTP transactions reach the coach lock before releasing it.
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname = current_database() AND wait_event_type = 'Lock'
                                  AND query ILIKE '%coaches%'
                                """, Integer.class)).isEqualTo(2));
                return List.of(first, second);
            });
            assertThat(requests).isNotNull();
            var responses = List.of(requests.get(0).get(10, TimeUnit.SECONDS), requests.get(1).get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(response -> response.getStatusCode())
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            var conflict = responses.stream().filter(response -> response.getStatusCode().equals(HttpStatus.CONFLICT))
                    .findFirst().orElseThrow();
            assertThat(conflict.getBody()).isNotNull();
            assertThat(conflict.getBody().path("code").asText()).isEqualTo("COACH_OVERLAP");
            assertThat(sessionCount(coachId)).isEqualTo(1);
        }
    }

    @Test
    void documentsTheSessionRequestResponseAndErrorStatuses() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        var operation = response.getBody().path("paths").path("/api/sessions").path("post");
        assertThat(operation.path("operationId").asText()).isEqualTo("createSession");
        assertThat(operation.path("responses").fieldNames()).toIterable().contains("201", "400", "404", "409", "500");
        assertThat(operation.path("responses").path("201").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/SessionResponse");
    }

    private UUID createCoach() {
        var response = http.postForEntity("/api/coaches",
                Map.of("name", "Alex Rivera", "email", "alex-" + UUID.randomUUID() + "@example.com"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private long sessionCount(UUID coachId) {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE coach_id = ?", Long.class, coachId);
    }

    private static Map<String, Object> payload(UUID coachId, String start, String end) {
        return Map.of("coachId", coachId.toString(), "startTime", start, "endTime", end, "capacity", 10, "location", "Court A");
    }

    private static Stream<Arguments> windows() {
        return Stream.of(
                Arguments.of("identical", "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z", true),
                Arguments.of("partial intersection before", "2026-10-05T13:30:00Z", "2026-10-05T14:30:00Z", true),
                Arguments.of("partial intersection after", "2026-10-05T14:30:00Z", "2026-10-05T15:30:00Z", true),
                Arguments.of("contained", "2026-10-05T14:15:00Z", "2026-10-05T14:45:00Z", true),
                Arguments.of("contains existing", "2026-10-05T13:00:00Z", "2026-10-05T16:00:00Z", true),
                Arguments.of("same start", "2026-10-05T14:00:00Z", "2026-10-05T14:30:00Z", true),
                Arguments.of("same end", "2026-10-05T14:30:00Z", "2026-10-05T15:00:00Z", true),
                Arguments.of("equivalent offset instants", "2026-10-05T10:00:00-04:00", "2026-10-05T17:00:00+02:00", true),
                Arguments.of("adjacent before", "2026-10-05T13:00:00Z", "2026-10-05T14:00:00Z", false),
                Arguments.of("adjacent after with offset", "2026-10-05T11:00:00-04:00", "2026-10-05T12:00:00-04:00", false),
                Arguments.of("separate before", "2026-10-05T12:00:00Z", "2026-10-05T13:00:00Z", false),
                Arguments.of("separate after", "2026-10-05T16:00:00Z", "2026-10-05T17:00:00Z", false),
                Arguments.of("one microsecond overlap", "2026-10-05T14:59:59.999999Z", "2026-10-05T16:00:00Z", true),
                Arguments.of("nanoseconds normalize to adjacent boundary", "2026-10-05T15:00:00.000000999Z", "2026-10-05T16:00:00Z", false));
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("capacity", 0), Arguments.of("capacity", -1), Arguments.of("capacity", 1.5),
                Arguments.of("capacity", (Object) null),
                Arguments.of("location", "  "), Arguments.of("location", (Object) null),
                Arguments.of("coachId", "not-a-uuid"), Arguments.of("coachId", (Object) null),
                Arguments.of("startTime", (Object) null), Arguments.of("endTime", (Object) null),
                Arguments.of("startTime", "2026-10-05T14:00:00"), Arguments.of("endTime", "2026-10-05T15:00:00"),
                Arguments.of("startTime", "not-a-date"), Arguments.of("startTime", 1791208800),
                Arguments.of("endTime", "2026-10-05T13:00:00Z"), Arguments.of("endTime", "2026-10-05T14:00:00Z"),
                Arguments.of("endTime", "2026-10-05T14:00:00.000000999Z"));
    }
}
