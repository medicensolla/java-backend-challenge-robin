package com.example.jbc.registrations;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationRepository extends JpaRepository<Registration, UUID> {

    boolean existsBySessionIdAndParticipantId(UUID sessionId, UUID participantId);

    long countBySessionId(UUID sessionId);

    boolean existsBySessionId(UUID sessionId);

    @EntityGraph(attributePaths = "participant")
    List<Registration> findAllBySessionIdOrderByParticipantId(UUID sessionId);

    @Modifying
    @Query("delete from Registration r where r.session.id = :sessionId and r.id = :registrationId")
    int deleteBySessionAndRegistrationId(@Param("sessionId") UUID sessionId,
            @Param("registrationId") UUID registrationId);
}
