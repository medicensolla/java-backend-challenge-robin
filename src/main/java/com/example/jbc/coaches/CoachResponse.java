package com.example.jbc.coaches;

import java.util.UUID;

public record CoachResponse(UUID id, String name, String email) {
}
