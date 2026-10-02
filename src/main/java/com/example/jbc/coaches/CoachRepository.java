package com.example.jbc.coaches;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoachRepository extends JpaRepository<Coach, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select coach from Coach coach where coach.id = :id")
    Optional<Coach> findByIdForUpdate(@Param("id") UUID id);
}
