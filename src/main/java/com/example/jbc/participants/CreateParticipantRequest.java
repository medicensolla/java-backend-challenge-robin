package com.example.jbc.participants;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateParticipantRequest(
        @NotBlank(message = "must not be blank")
        @Schema(example = "Sam Lee", description = "Nonblank participant name; stored as supplied.") String name,
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email")
        @Schema(example = "sam@example.com") String email) {
}
