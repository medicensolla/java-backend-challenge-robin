package com.example.jbc.common;

import io.swagger.v3.oas.annotations.media.Schema;

public record FieldViolation(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "email") String field,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "must be a valid email") String message) {
}
