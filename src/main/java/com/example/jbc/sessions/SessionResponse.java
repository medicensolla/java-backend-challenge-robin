package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record SessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "33333333-3333-4333-8333-333333333333", format = "uuid") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "11111111-1111-4111-8111-111111111111", format = "uuid") UUID coachId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-05T14:00:00Z", format = "date-time", description = "UTC, truncated to microsecond precision.") Instant startTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-05T15:00:00Z", format = "date-time", description = "UTC, truncated to microsecond precision.") Instant endTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2") int capacity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Court A") String location) {
}
