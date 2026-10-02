package com.example.jbc.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record ApiError(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "INVALID_REQUEST") String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Bad Request") String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "Present only for field validation failures; omitted when empty.")
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "email") String field,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "must be a valid email") String message) {
    }
}
