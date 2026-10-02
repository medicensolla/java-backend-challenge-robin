package com.example.jbc.coaches;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record CoachResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "11111111-1111-4111-8111-111111111111", format = "uuid") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Alex Rivera", description = "Normalized public coach name.") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "alex@example.com", format = "email") String email) {
}
