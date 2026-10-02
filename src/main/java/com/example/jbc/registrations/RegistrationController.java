package com.example.jbc.registrations;

import java.util.UUID;

import com.example.jbc.common.ApiError;
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
@RequestMapping(value = "/api/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Registrations")
public class RegistrationController {

    private final RegistrationService service;

    @PostMapping(value = "/registrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createRegistration", summary = "Register a participant",
            description = "Check session, participant, duplicate registration and capacity in that order. "
                    + "Duplicate takes precedence over full capacity. Concurrent capacity protection is not included.")
    @ApiResponse(responseCode = "201", description = "Registration created")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Session or participant was not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Duplicate registration or full session",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public RegistrationResponse create(@PathVariable UUID sessionId, @Valid @RequestBody CreateRegistrationRequest request) {
        return service.create(sessionId, request);
    }

    @GetMapping("/participants")
    @Operation(operationId = "listSessionParticipants", summary = "List registered participants",
            description = "Include participant details and registration IDs, ordered by participant ID. "
                    + "Returns a page with totals. An existing empty session returns empty content; a missing session returns 404.")
    @ApiResponse(responseCode = "200", description = "Registered participants")
    @ApiResponse(responseCode = "400", description = "Invalid session UUID or pagination",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Session was not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PageResponse<RegisteredParticipantResponse> listParticipants(@PathVariable UUID sessionId,
            @Parameter(description = "Zero-based page; omitted or empty values use 0.")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size from 1 to 100; omitted or empty values use 20.")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listParticipants(sessionId, page, size);
    }

    @DeleteMapping("/registrations/{registrationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelRegistration", summary = "Cancel a registration",
            description = "Check session existence first. Remove only the registration belonging to this session. "
                    + "A missing registration or one in another session returns 404. Cancellation frees capacity.")
    @ApiResponse(responseCode = "204", description = "Registration cancelled", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Session or registration was not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void cancel(@PathVariable UUID sessionId, @PathVariable UUID registrationId) {
        service.cancel(sessionId, registrationId);
    }
}
