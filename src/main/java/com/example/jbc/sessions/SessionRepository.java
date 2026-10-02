package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<TrainingSession, UUID> {

    boolean existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID coachId, Instant endTime, Instant startTime);
}
