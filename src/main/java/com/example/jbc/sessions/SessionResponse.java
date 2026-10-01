package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id, UUID coachId, Instant startTime, Instant endTime, int capacity, String location) {
}
