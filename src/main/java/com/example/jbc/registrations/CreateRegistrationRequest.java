package com.example.jbc.registrations;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CreateRegistrationRequest(
        @NotNull(message = "must not be null")
        @Schema(example = "22222222-2222-4222-8222-222222222222") UUID participantId) {
}
