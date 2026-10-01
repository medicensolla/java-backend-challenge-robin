package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;

import com.example.jbc.common.OffsetInstantDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "A half-open interval [startTime, endTime). Start must precede end at microsecond precision; past sessions are allowed.")
public record CreateSessionRequest(
        @NotNull(message = "must not be null") UUID coachId,
        @NotNull(message = "must not be null")
        @JsonDeserialize(using = OffsetInstantDeserializer.class)
        @Schema(type = "string", format = "date-time", example = "2026-10-05T10:00:00-04:00",
                description = "ISO-8601 with explicit offset; stored at microsecond precision.") Instant startTime,
        @NotNull(message = "must not be null")
        @JsonDeserialize(using = OffsetInstantDeserializer.class)
        @Schema(type = "string", format = "date-time", example = "2026-10-05T11:00:00-04:00",
                description = "ISO-8601 with explicit offset; stored at microsecond precision.") Instant endTime,
        @NotNull(message = "must not be null")
        @Positive(message = "must be greater than zero") Integer capacity,
        @NotBlank(message = "must not be blank") String location) {
}
