package com.example.jbc.common;

import java.net.URI;
import java.util.Comparator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldViolation::field)
                        .thenComparing(FieldViolation::message))
                .toList();
        var body = problem(status, "INVALID_REQUEST", "Request contains invalid fields.", request);
        if (!violations.isEmpty()) {
            body.setProperty("fieldErrors", violations);
        }
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var body = problem(status, "INVALID_REQUEST", "Request body must contain valid JSON.", request);
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (!(body instanceof ProblemDetail problem) || problem.getProperties() == null
                || !problem.getProperties().containsKey("code")) {
            body = problem(status, status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST",
                    status.is5xxServerError() ? "An unexpected error occurred."
                            : HttpStatus.valueOf(status.value()).getReasonPhrase(), request);
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(exception, body, responseHeaders, status, request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, WebRequest request) {
        return ResponseEntity.status(exception.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(exception.getStatus(), exception.getCode(), exception.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, WebRequest request) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request));
    }

    private ProblemDetail problem(HttpStatusCode status, String code, String detail, WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        body.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
        body.setProperty("code", code);
        return body;
    }
}
