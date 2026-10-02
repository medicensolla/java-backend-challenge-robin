package com.example.jbc.sessions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.jbc.common.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Coach was not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Coach has an overlapping session",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SessionResponse create(@Valid @RequestBody CreateSessionRequest request) {
        return service.create(request);
    }

    @GetMapping
    @Operation(operationId = "listSessions", summary = "List and filter sessions",
            description = "Optional filters combine with AND. Select sessions intersecting [from, to): endTime > from and startTime < to. "
                    + "Either bound may be omitted. Order by startTime then ID. No matches return an empty array.")
    @ApiResponse(responseCode = "200", description = "Matching sessions")
    @ApiResponse(responseCode = "400", description = "Invalid filter or time interval",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<SessionResponse> list(
            @Parameter(description = "Exact coach ID; unknown coaches match no sessions.")
            @RequestParam(required = false) UUID coachId,
            @Parameter(description = "ISO-8601 with explicit offset; exclusive test against session end. Truncated to microseconds.",
                    example = "2026-10-05T14:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "ISO-8601 with explicit offset; exclusive upper bound against session start. Truncated to microseconds.",
                    example = "2026-10-05T15:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return service.list(coachId, from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
    }
}
