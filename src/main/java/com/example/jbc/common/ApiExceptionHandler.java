package com.example.jbc.common;

import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(ApiError.FieldViolation::field)
                        .thenComparing(ApiError.FieldViolation::message))
                .toList();
        var body = new ApiError("INVALID_REQUEST", "Request contains invalid fields.", violations);
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var body = new ApiError("INVALID_REQUEST", "Request body must contain valid JSON.", List.of());
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (!(body instanceof ApiError)) {
            body = status.is5xxServerError()
                    ? new ApiError("INTERNAL_ERROR", "An unexpected error occurred.", List.of())
                    : new ApiError("INVALID_REQUEST", HttpStatus.valueOf(status.value()).getReasonPhrase(), List.of());
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred.", List.of()));
    }
}
