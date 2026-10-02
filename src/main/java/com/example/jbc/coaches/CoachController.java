package com.example.jbc.coaches;

import com.example.jbc.common.ApiProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/coaches", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Coaches")
public class CoachController {

    private final CoachService service;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createCoach", summary = "Create a coach",
            description = "Normalize surrounding and repeated ASCII whitespace in the name. "
                    + "Internally split the first word from the remainder while keeping the public name field.")
    @ApiResponse(responseCode = "201", description = "Resource created")
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemSchema.class)))
    public CoachResponse create(@Valid @RequestBody CreateCoachRequest request) {
        return service.create(request);
    }
}
