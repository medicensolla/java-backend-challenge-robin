package com.example.jbc.participants;

import java.util.UUID;

public record ParticipantResponse(UUID id, String name, String email) {
}
