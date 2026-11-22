package com.eventflow.catalog.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.eventflow.catalog.shared.domain.BusinessRuleException;
import com.eventflow.catalog.shared.domain.EntityNotFoundException;
import com.eventflow.catalog.shared.domain.StateConflictException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps expected failures to RFC 9457 problem responses with a stable {@code code} and the
 * request {@code traceId}. Unexpected exceptions are logged with the same trace id and
 * never leak internal details to the client.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(StateConflictException.class)
    public ProblemDetail handleConflict(StateConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body validation failed", request);
        problem.setProperty("errors", fieldErrors(exception));
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleInvalidParameters(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request parameter validation failed", request);
        problem.setProperty("errors", resolutionErrors(exception.getAllErrors()));
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Parameter '" + exception.getName() + "' has an invalid value",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleUnknownPath(NoResourceFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No resource at " + request.getRequestURI(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataConflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Database constraint violation [traceId={}]", TraceId.current(), exception);
        return problem(
                HttpStatus.CONFLICT, "DATA_CONFLICT", "The request violates a data constraint", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled failure [traceId={}]", TraceId.current(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("urn:eventflow:error:" + code.toLowerCase(Locale.ROOT)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", TraceId.current());
        return problem;
    }

    private List<Map<String, String>> fieldErrors(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .map(this::errorEntry)
                .toList();
    }

    private Map<String, String> errorEntry(FieldError error) {
        return Map.of(
                "field",
                error.getField(),
                "message",
                Optional.ofNullable(error.getDefaultMessage()).orElse("invalid value"));
    }

    private List<Map<String, String>> resolutionErrors(List<? extends MessageSourceResolvable> errors) {
        return errors.stream()
                .map(error -> Map.of(
                        "message",
                        Optional.ofNullable(error.getDefaultMessage()).orElse("invalid value")))
                .toList();
    }
}
