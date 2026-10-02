package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<TrainingSession, UUID>, JpaSpecificationExecutor<TrainingSession> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from TrainingSession session where session.id = :id")
    Optional<TrainingSession> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID coachId, Instant endTime, Instant startTime);

    @Override
    @EntityGraph(attributePaths = "coach")
    Page<TrainingSession> findAll(Specification<TrainingSession> specification, Pageable pageable);

    @Modifying
    @Query("delete from TrainingSession s where s.id = :sessionId")
    int deleteSessionById(@Param("sessionId") UUID sessionId);
}
