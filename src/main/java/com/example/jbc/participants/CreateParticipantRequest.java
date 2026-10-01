package com.example.jbc.participants;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateParticipantRequest(
        @NotBlank(message = "must not be blank") String name,
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email") String email) {
}
