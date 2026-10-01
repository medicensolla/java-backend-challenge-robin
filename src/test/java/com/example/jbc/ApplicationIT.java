package com.example.jbc;

import java.time.Instant;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
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
}
