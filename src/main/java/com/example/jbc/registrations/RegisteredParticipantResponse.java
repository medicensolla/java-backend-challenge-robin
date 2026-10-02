package com.example.jbc.registrations;

import java.util.UUID;

public record RegisteredParticipantResponse(UUID id, String name, String email, UUID registrationId) {
}
