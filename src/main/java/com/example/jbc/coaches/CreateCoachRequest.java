package com.example.jbc.coaches;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateCoachRequest(
        @NotBlank(message = "must not be blank")
        @Schema(example = "  Alex   Rivera  ", description = "Surrounding and repeated ASCII whitespace is normalized to single spaces.") String name,
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email")
        @Schema(example = "alex@example.com") String email) {
}
