package com.example.jbc.registrations;

import java.util.UUID;

public record RegistrationResponse(UUID id, UUID sessionId, UUID participantId) {
}
