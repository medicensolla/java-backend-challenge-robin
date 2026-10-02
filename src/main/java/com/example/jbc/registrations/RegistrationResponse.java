package com.example.jbc.registrations;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegistrationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "44444444-4444-4444-8444-444444444444", format = "uuid", description = "Registration ID; use it when cancelling.") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "33333333-3333-4333-8333-333333333333", format = "uuid") UUID sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "22222222-2222-4222-8222-222222222222", format = "uuid") UUID participantId) {
}
