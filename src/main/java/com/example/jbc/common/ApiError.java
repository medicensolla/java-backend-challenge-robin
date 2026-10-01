package com.example.jbc.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiError(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }
}
