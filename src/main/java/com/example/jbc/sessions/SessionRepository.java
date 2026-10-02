package com.example.jbc.sessions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SessionRepository extends JpaRepository<TrainingSession, UUID>, JpaSpecificationExecutor<TrainingSession> {

    boolean existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID coachId, Instant endTime, Instant startTime);

    @Override
    @EntityGraph(attributePaths = "coach")
    List<TrainingSession> findAll(Specification<TrainingSession> specification, Sort sort);
}
