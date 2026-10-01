package com.example.jbc;

import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CoachesMigrationIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void useAnIsolatedSchema() {
        schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        var url = postgres.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migratesAnEmptyDatabaseAndDoesNotReapplyOnRestart() {
        var initialFlyway = newFlyway();

        assertThat(initialFlyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT column_name || ':' || udt_name || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'coaches'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("id:uuid:NO", "name:text:NO", "email:text:NO");

        var coachId = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                coachId, "Alex Rivera", "alex@example.com");
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                UUID.randomUUID(), "Another Alex", "alex@example.com");
        var originalChecksum = initialFlyway.info().current().getChecksum();

        var restartedFlyway = newFlyway();
        restartedFlyway.validate();

        assertThat(restartedFlyway.migrate().migrationsExecuted).isZero();
        assertThat(restartedFlyway.info().current().getVersion().toString()).isEqualTo("1");
        assertThat(restartedFlyway.info().current().getChecksum()).isEqualTo(originalChecksum);
        assertThat(jdbc.queryForObject("SELECT name FROM coaches WHERE id = ?", String.class, coachId))
                .isEqualTo("Alex Rivera");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coaches", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success
                """, Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest(name = "rejects null {0}")
    @MethodSource("coachesWithNullFields")
    void rejectsNullRequiredFields(String field, UUID id, String name, String email) {
        newFlyway().migrate();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                id, name, email))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(field);
    }

    @Test
    void rejectsDuplicateCoachIds() {
        newFlyway().migrate();
        var coachId = UUID.randomUUID();
        jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                coachId, "Alex Rivera", "alex@example.com");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO coaches (id, name, email) VALUES (?, ?, ?)",
                coachId, "Sam Lee", "sam@example.com"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private Flyway newFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                // Keep the V1 smoke test independent of later migrations.
                .target("1")
                .cleanDisabled(true)
                .load();
    }

    private static Stream<Arguments> coachesWithNullFields() {
        return Stream.of(
                Arguments.of("id", null, "Alex Rivera", "alex@example.com"),
                Arguments.of("name", UUID.randomUUID(), null, "alex@example.com"),
                Arguments.of("email", UUID.randomUUID(), "Alex Rivera", null));
    }
}
