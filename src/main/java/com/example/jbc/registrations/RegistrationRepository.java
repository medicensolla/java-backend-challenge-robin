package com.example.jbc.registrations;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationRepository extends JpaRepository<Registration, UUID> {

    boolean existsBySessionIdAndParticipantId(UUID sessionId, UUID participantId);

    long countBySessionId(UUID sessionId);
}
