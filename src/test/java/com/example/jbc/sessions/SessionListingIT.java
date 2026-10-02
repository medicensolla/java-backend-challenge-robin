package com.example.jbc.sessions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionListingIT {

    private static final UUID COACH_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID COACH_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID EMPTY_COACH = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID UNKNOWN_COACH = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID EARLY_B = id(4);
    private static final UUID EARLY_A = id(5);
    private static final UUID TIED_A = id(9);
    private static final UUID TIED_B = id(3);
    private static final UUID LATE_A = id(1);
    private static final UUID LATE_B = id(2);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void seedIndependentSchedule() {
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM coaches");
        for (var coachId : List.of(COACH_A, COACH_B, EMPTY_COACH)) {
            jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                    coachId, "Alex Rivera", "alex@example.com");
        }
        // Deliberately insert out of order and give the tied sessions deterministic UUIDs.
        insertSession(LATE_A, COACH_A, "12:00:00", "13:00:00");
        insertSession(TIED_A, COACH_A, "10:00:00", "11:00:00");
        insertSession(LATE_B, COACH_B, "13:00:00", "14:00:00");
        insertSession(EARLY_A, COACH_A, "09:00:00", "10:00:00");
        insertSession(TIED_B, COACH_B, "10:00:00", "12:00:00");
        insertSession(EARLY_B, COACH_B, "08:00:00", "09:00:00");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filters")
    void listsExactlyTheMatchingSessionsInStableOrder(String description, Map<String, String> filters, List<UUID> expected) {
        var response = get(filters);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("content").isArray()).isTrue();
        assertThat(response.getBody().path("totalElements").asInt()).isEqualTo(expected.size());
        assertThat(response.getBody().path("totalPages").asInt()).isEqualTo(expected.isEmpty() ? 0 : 1);
        var ids = new ArrayList<UUID>();
        response.getBody().path("content").forEach(session -> ids.add(UUID.fromString(session.path("id").asText())));
        assertThat(ids).containsExactlyElementsOf(expected);
        response.getBody().path("content").forEach(session -> {
            assertThat(session.size()).isEqualTo(6);
            assertThat(UUID.fromString(session.path("coachId").asText())).isIn(COACH_A, COACH_B);
            assertThat(session.path("startTime").asText()).endsWith("Z");
            assertThat(session.path("endTime").asText()).endsWith("Z");
            assertThat(session.path("capacity").asInt()).isEqualTo(10);
            assertThat(session.path("location").asText()).isEqualTo("Court A");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFilters")
    void rejectsInvalidFiltersWithSafeBadRequestErrors(String description, Map<String, String> filters) {
        var response = get(filters);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().path("message").asText()).isNotBlank();
        assertThat(response.getBody().has("trace")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Long.class)).isEqualTo(6);
    }

    @Test
    void anEmptyDatabaseReturnsAnEmptyPage() {
        jdbc.update("DELETE FROM sessions");

        var response = get(Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("content").isArray()).isTrue();
        assertThat(response.getBody().path("content").isEmpty()).isTrue();
    }

    @Test
    void listsSessionsWithOneContentAndOneCountQuery() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var response = get(Map.of("size", "2"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("content").size()).isEqualTo(2);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void documentsTheOptionalFiltersAndPageResponse() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        var operation = response.getBody().path("paths").path("/api/sessions").path("get");
        assertThat(operation.path("operationId").asText()).isEqualTo("listSessions");
        assertThat(operation.path("responses").fieldNames()).toIterable().containsExactlyInAnyOrder("200", "400", "500");
        var schema = operation.path("responses").path("200").path("content").path("application/json").path("schema");
        var pageSchema = response.getBody().path("components").path("schemas")
                .path(schema.path("$ref").asText().replace("#/components/schemas/", ""));
        assertThat(pageSchema.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        assertThat(pageSchema.path("properties").path("content").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/SessionResponse");
        assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("coachId", "from", "to", "page", "size");
        operation.path("parameters").forEach(parameter -> assertThat(parameter.path("required").asBoolean()).isFalse());
    }

    @Test
    void pagesKeepStableOrderAcrossTiesAndReportFilteredTotals() {
        var expected = List.of(EARLY_B, EARLY_A, TIED_B, TIED_A, LATE_A, LATE_B);
        var actual = new ArrayList<UUID>();
        for (int page = 0; page < 2; page++) {
            var body = get(Map.of("page", Integer.toString(page), "size", "3")).getBody();
            assertThat(body.path("page").asInt()).isEqualTo(page);
            assertThat(body.path("size").asInt()).isEqualTo(3);
            assertThat(body.path("totalElements").asInt()).isEqualTo(6);
            assertThat(body.path("totalPages").asInt()).isEqualTo(2);
            body.path("content").forEach(item -> actual.add(UUID.fromString(item.path("id").asText())));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        var last = get(Map.of("coachId", COACH_A.toString(), "page", "1", "size", "2")).getBody();
        assertThat(last.path("content").size()).isEqualTo(1);
        assertThat(last.path("content").get(0).path("id").asText()).isEqualTo(LATE_A.toString());
        assertThat(last.path("totalElements").asInt()).isEqualTo(3);
        assertThat(last.path("totalPages").asInt()).isEqualTo(2);
        var beyond = get(Map.of("page", "10", "size", "2")).getBody();
        assertThat(beyond.path("content").isEmpty()).isTrue();
        assertThat(beyond.path("totalElements").asInt()).isEqualTo(6);
        assertThat(beyond.path("totalPages").asInt()).isEqualTo(3);
        var defaults = get(Map.of("page", "", "size", "")).getBody();
        assertThat(defaults.path("page").asInt()).isZero();
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(get(Map.of("size", "100")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "page=-1", "size=0", "size=-1", "size=101", "page=1.5", "size=abc", "page=2147483648"
    })
    void rejectsInvalidPagination(String query) {
        var response = http.getForEntity("/api/sessions?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    private ResponseEntity<JsonNode> get(Map<String, String> filters) {
        var uri = UriComponentsBuilder.fromUriString(http.getRootUri() + "/api/sessions");
        filters.forEach((name, value) -> uri.queryParam(name, "{" + name + "}"));
        // Encode variables strictly so a positive offset's '+' reaches the API unchanged.
        return http.getForEntity(uri.encode().buildAndExpand(filters).toUri(), JsonNode.class);
    }

    private void insertSession(UUID id, UUID coachId, String start, String end) {
        jdbc.update("""
                INSERT INTO sessions (id, coach_id, start_time, end_time, capacity, location)
                VALUES (?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 10, 'Court A')
                """, id, coachId, "2026-10-05T" + start + "Z", "2026-10-05T" + end + "Z");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("11111111-1111-4111-8111-" + String.format("%012d", suffix));
    }

    private static Stream<Arguments> filters() {
        return Stream.of(
                Arguments.of("unfiltered, ordered by start then UUID", Map.of(),
                        List.of(EARLY_B, EARLY_A, TIED_B, TIED_A, LATE_A, LATE_B)),
                Arguments.of("empty optional values are treated as omitted", Map.of("coachId", "", "from", "", "to", ""),
                        List.of(EARLY_B, EARLY_A, TIED_B, TIED_A, LATE_A, LATE_B)),
                Arguments.of("coach only", Map.of("coachId", COACH_A.toString()), List.of(EARLY_A, TIED_A, LATE_A)),
                Arguments.of("unknown coach", Map.of("coachId", UNKNOWN_COACH.toString()), List.of()),
                Arguments.of("existing coach without sessions", Map.of("coachId", EMPTY_COACH.toString()), List.of()),
                Arguments.of("lower bound only excludes sessions ending at from", Map.of("from", "2026-10-05T11:00:00Z"),
                        List.of(TIED_B, LATE_A, LATE_B)),
                Arguments.of("upper bound only excludes sessions starting at to", Map.of("to", "2026-10-05T10:00:00Z"),
                        List.of(EARLY_B, EARLY_A)),
                Arguments.of("date range excludes both touching boundaries",
                        Map.of("from", "2026-10-05T10:00:00Z", "to", "2026-10-05T12:00:00Z"), List.of(TIED_B, TIED_A)),
                Arguments.of("coach and both bounds combine with AND",
                        Map.of("coachId", COACH_A.toString(), "from", "2026-10-05T10:00:00Z", "to", "2026-10-05T12:00:00Z"),
                        List.of(TIED_A)),
                Arguments.of("coach and lower bound", Map.of("coachId", COACH_A.toString(), "from", "2026-10-05T10:00:00Z"),
                        List.of(TIED_A, LATE_A)),
                Arguments.of("coach and upper bound", Map.of("coachId", COACH_B.toString(), "to", "2026-10-05T10:00:00Z"),
                        List.of(EARLY_B)),
                Arguments.of("equivalent positive and negative offsets",
                        Map.of("from", "2026-10-05T12:00:00+02:00", "to", "2026-10-05T08:00:00-04:00"), List.of(TIED_B, TIED_A)),
                Arguments.of("sessions containing the entire filter interval",
                        Map.of("from", "2026-10-05T10:15:00Z", "to", "2026-10-05T10:45:00Z"), List.of(TIED_B, TIED_A)),
                Arguments.of("partial intersections on either side",
                        Map.of("from", "2026-10-05T12:30:00Z", "to", "2026-10-05T13:30:00Z"), List.of(LATE_A, LATE_B)),
                Arguments.of("no dates match", Map.of("from", "2026-10-06T00:00:00Z"), List.of()),
                Arguments.of("submicrosecond upper bound normalizes to the exclusive boundary",
                        Map.of("to", "2026-10-05T10:00:00.000000999Z"), List.of(EARLY_B, EARLY_A)),
                Arguments.of("one microsecond filter interval",
                        Map.of("from", "2026-10-05T10:00:00.000000999Z", "to", "2026-10-05T10:00:00.000001099Z"),
                        List.of(TIED_B, TIED_A)));
    }

    private static Stream<Arguments> invalidFilters() {
        return Stream.of(
                Arguments.of("invalid coach UUID", Map.of("coachId", "not-a-uuid")),
                Arguments.of("from without offset", Map.of("from", "2026-10-05T10:00:00")),
                Arguments.of("to without offset", Map.of("to", "2026-10-05T12:00:00")),
                Arguments.of("invalid from", Map.of("from", "not-a-date")),
                Arguments.of("invalid to", Map.of("to", "2026-02-30T12:00:00Z")),
                Arguments.of("numeric epoch", Map.of("from", "1791208800")),
                Arguments.of("equal range", Map.of("from", "2026-10-05T10:00:00Z", "to", "2026-10-05T10:00:00Z")),
                Arguments.of("reversed range", Map.of("from", "2026-10-05T12:00:00Z", "to", "2026-10-05T10:00:00Z")),
                Arguments.of("equal instants in different offsets",
                        Map.of("from", "2026-10-05T12:00:00+02:00", "to", "2026-10-05T06:00:00-04:00")),
                Arguments.of("reversed instants despite earlier local from time",
                        Map.of("from", "2026-10-05T08:00:00-04:00", "to", "2026-10-05T11:00:00+02:00")),
                Arguments.of("range collapsing at microsecond precision",
                        Map.of("from", "2026-10-05T10:00:00.000000100Z", "to", "2026-10-05T10:00:00.000000999Z")));
    }
}
