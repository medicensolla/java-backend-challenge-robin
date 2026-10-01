package com.example.jbc.sessions;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<TrainingSession, UUID> {
}
