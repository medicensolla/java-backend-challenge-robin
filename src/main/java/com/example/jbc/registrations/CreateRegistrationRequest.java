package com.example.jbc.registrations;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateRegistrationRequest(
        @NotNull(message = "must not be null") UUID participantId) {
}
