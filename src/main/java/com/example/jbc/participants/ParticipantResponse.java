package com.example.jbc.participants;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record ParticipantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "22222222-2222-4222-8222-222222222222", format = "uuid") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Sam Lee") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "sam@example.com", format = "email") String email) {
}
