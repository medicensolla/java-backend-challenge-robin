package com.example.jbc.common;

import java.net.URI;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documents the ProblemDetail wire format, including application extensions. */
@Schema(name = "ApiProblem")
public record ApiProblemSchema(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "about:blank") URI type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Bad Request") String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "400") int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Request contains invalid fields.") String detail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "/api/coaches") URI instance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "INVALID_REQUEST") String code,
        @Schema(description = "Present only for body field validation failures; omitted when empty.")
        List<FieldViolation> fieldErrors) {
}
