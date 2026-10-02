package com.example.jbc.sessions;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.jbc.common.ApiProblemSchema;
import com.example.jbc.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Sessions")
public class SessionController {

    private final SessionService service;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSession", summary = "Create a session",
            description = "Validate input, then coach existence, then overlap. Adjacent sessions and shared times for different coaches are allowed. Creation is serialized per coach.")
    @ApiResponse(responseCode = "201", description = "Session created")
    @ApiResponse(responseCode = "400", description = "Invalid request or time window",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "Coach was not found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "Coach has an overlapping session",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    public SessionResponse create(@Valid @RequestBody CreateSessionRequest request) {
        return service.create(request);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteSession", summary = "Delete an empty session",
            description = "Check session existence first. Cancel all registrations before deleting the session. "
                    + "The database foreign key also blocks deletion if a registration appears after the check.")
    @ApiResponse(responseCode = "204", description = "Session deleted", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid session UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "Session was not found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "Session has registrations",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    public void delete(@PathVariable UUID sessionId) {
        service.delete(sessionId);
    }

    @GetMapping
    @Operation(operationId = "listSessions", summary = "List and filter sessions",
            description = "Optional filters combine with AND. Select sessions intersecting [from, to): endTime > from and startTime < to. "
                    + "Either bound may be omitted. Order by startTime then ID. Returns a page with totals; no matches return empty content.")
    @ApiResponse(responseCode = "200", description = "Matching sessions")
    @ApiResponse(responseCode = "400", description = "Invalid filter, time interval or pagination",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    public PageResponse<SessionResponse> list(
            @Parameter(description = "Exact coach ID; unknown coaches match no sessions.")
            @RequestParam(required = false) UUID coachId,
            @Parameter(description = "ISO-8601 with explicit offset; exclusive test against session end. Truncated to microseconds.",
                    example = "2026-10-05T14:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "ISO-8601 with explicit offset; exclusive upper bound against session start. Truncated to microseconds.",
                    example = "2026-10-05T15:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Zero-based page; omitted or empty values use 0.")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size from 1 to 100; omitted or empty values use 20.")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(coachId, from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), page, size);
    }
}
