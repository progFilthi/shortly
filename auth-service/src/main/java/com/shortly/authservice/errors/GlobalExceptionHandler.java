package com.shortly.authservice.errors;

import com.shortly.authservice.exceptions.AuthException;
import com.shortly.contracts.errors.ApiError;
import com.shortly.contracts.errors.ProblemProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every escaping exception into one consistent RFC 9457 problem document.
 *
 * <p>Without this, a validation failure and a genuine bug both surface as an HTML error page or a
 * bare 500, so a client cannot tell "fix your request" from "retry later" without string
 * matching. Every response carries a {@code code} from {@link ApiError}, which is shared across
 * services, so a client branches on the code rather than on the message.
 *
 * <p>Unhandled exceptions get a {@code traceId} that also appears in the log, so a user can quote
 * it and an operator can find the stack trace. The exception message itself is never returned:
 * it routinely contains SQL, file paths, or attacker-supplied input.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://shortly.dev/problems/";

    /* --------------------------- domain exceptions ---------------------------- */

    /**
     * Every auth-service failure is an {@link AuthException} carrying its own code, so one
     * handler covers all of them and adding a new failure type needs no change here.
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> handleAuthException(AuthException e,
                                                            HttpServletRequest request) {
        ProblemDetail detail = problem(e.error(), e.getMessage(), request);
        // Each exception contributes its own structured detail, e.g. the lockout countdown or the
        // minimum password length. No instanceof chain here, so adding a failure type needs no
        // change to this handler.
        e.problemDetails().forEach(detail::setProperty);

        log.warn("{} on {}: {} ({})",
                e.error().wireValue(), request.getRequestURI(), e.getMessage(), detail.getProperties());
        return ResponseEntity.status(HttpStatus.valueOf(e.error().httpStatus()))
                .body(detail);
    }

    /* ----------------------------- request problems ----------------------------- */

    /** Bean-validation failures on a request body, reported per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<String> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();

        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        org.springframework.validation.FieldError::getDefaultMessage,
                        (first, second) -> first));

        ProblemDetail detail = problem(ApiError.VALIDATION_FAILED,
                "One or more fields are invalid.", request);
        detail.setProperty(ProblemProperties.VIOLATIONS, violations);
        detail.setProperty(ProblemProperties.FIELD_ERRORS, fieldErrors);

        log.debug("Validation failed on {}: {}", request.getRequestURI(), violations);
        return ResponseEntity.badRequest().body(detail);
    }

    /** Bean-validation failures on a path variable or request parameter. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException e,
                                                                  HttpServletRequest request) {
        List<String> violations = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .toList();

        ProblemDetail detail = problem(ApiError.VALIDATION_FAILED,
                "One or more values are invalid.", request);
        detail.setProperty(ProblemProperties.VIOLATIONS, violations);
        return ResponseEntity.badRequest().body(detail);
    }

    /** Body was not valid JSON, or a field had the wrong type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException e,
                                                             HttpServletRequest request) {
        // The parser message names internal classes and would be noise to a client.
        log.debug("Unreadable body on {}: {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(problem(ApiError.MALFORMED_REQUEST,
                        "Request body is missing or is not valid JSON.", request));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException e,
                                                              HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(ApiError.MISSING_REQUIRED_VALUE,
                        "Required header '" + e.getHeaderName() + "' is missing.", request));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException e,
                                                                HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(ApiError.MISSING_REQUIRED_VALUE,
                        "Required parameter '" + e.getParameterName() + "' is missing.", request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(ApiError.VALIDATION_FAILED,
                        "'" + e.getName() + "' has an invalid format.", request));
    }

    /* ------------------------------ routing problems ----------------------------- */

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException e,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(ApiError.NOT_FOUND, "No endpoint matches this path.", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        ProblemDetail detail = problem(ApiError.METHOD_NOT_ALLOWED,
                "HTTP method not supported for this path.", request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, String.join(", ", e.getSupportedMethods() != null
                        ? e.getSupportedMethods() : new String[0]))
                .body(detail);
    }

    /* ------------------------------ security problems ---------------------------- */

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(ApiError.ACCESS_DENIED,
                        "You do not have access to this resource.", request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException e,
                                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(problem(ApiError.UNAUTHENTICATED,
                        "Authentication is required.", request));
    }

    /* ----------------------------- conflict problems ----------------------------- */

    /**
     * A unique-constraint violation that no earlier check caught.
     * <p>
     * Reached only on a genuine race, since registration also pre-checks. The constraint name is
     * not echoed back, because it names a column and would tell an attacker which field collided.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrityViolation(DataIntegrityViolationException e,
                                                                  HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(ApiError.CONFLICTING_STATE,
                        "That value is already in use.", request));
    }

    /* --------------------------------- fallback ---------------------------------- */

    /**
     * Anything unhandled.
     * <p>
     * A fresh trace id per failure, returned to the client and written to the log. A 500 with a
     * generic message and a correlatable id is the difference between a five-minute triage and an
     * afternoon of "the user says it sometimes errors".
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();

        log.error("Unhandled exception on {} (traceId={})", request.getRequestURI(), traceId, e);

        ProblemDetail detail = problem(ApiError.INTERNAL_ERROR,
                "Something went wrong on our side. Quote this trace id if you report it: "
                        + traceId, request);
        detail.setProperty(ProblemProperties.TRACE_ID, traceId);
        return ResponseEntity.status(HttpStatus.valueOf(ApiError.INTERNAL_ERROR.httpStatus())).body(detail);
    }

    /* ---------------------------------- helpers ---------------------------------- */

    private ProblemDetail problem(ApiError error, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(error.httpStatus()), detail);
        problem.setTitle(error.wireValue());
        problem.setType(URI.create(PROBLEM_BASE + error.wireValue()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty(ProblemProperties.CODE, error.wireValue());
        return problem;
    }
}
