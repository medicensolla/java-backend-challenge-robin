package com.example.jbc.participants;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM participants
                WHERE lower(normalize_coach_name(name)) = lower(normalize_coach_name(:name))
                  AND lower(btrim(email, E' \\t\\n\\r\\f\\x0B')) =
                      lower(btrim(:email, E' \\t\\n\\r\\f\\x0B'))
            )
            """, nativeQuery = true)
    boolean existsByNormalizedNameAndEmail(@Param("name") String name, @Param("email") String email);
}
