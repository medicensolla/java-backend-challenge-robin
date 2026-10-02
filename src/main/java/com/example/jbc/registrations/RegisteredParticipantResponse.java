package com.example.jbc.registrations;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegisteredParticipantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "22222222-2222-4222-8222-222222222222", format = "uuid", description = "Participant ID.") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Sam Lee") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "sam@example.com", format = "email") String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "44444444-4444-4444-8444-444444444444", format = "uuid", description = "Registration ID; use it when cancelling.") UUID registrationId) {
}
