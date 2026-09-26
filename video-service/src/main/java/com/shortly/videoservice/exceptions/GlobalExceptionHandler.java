package com.shortly.videoservice.exceptions;

import com.shortly.contracts.errors.ApiError;
import com.shortly.contracts.errors.ProblemProperties;
import com.shortly.videoservice.security.CallerIdentityArgumentResolver;
import com.shortly.videoservice.services.UploadVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import java.util.stream.Collectors;

/**
 * Turns every escaping exception into one consistent RFC 9457 problem document.
 *
 * <p>Why this matters concretely: without it, an oversized upload and a genuine bug both arrive as
 * a 500, so the client retries the first forever and never reports the second. Every response
 * carries a {@code code} from the shared {@link ApiError}, so a client branches on the code rather
 * than pattern-matching a message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://shortly.dev/problems/";

    /* ----------------------------- domain exceptions ---------------------------- */

    @ExceptionHandler(VideoNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(VideoNotFoundException e,
                                                       HttpServletRequest request) {
        return respond(ApiError.NOT_FOUND, e.getMessage(), request);
    }

    /** 403, not 404: the caller is authenticated and simply does not own this video. */
    @ExceptionHandler(VideoNotAuthorizedException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(VideoNotAuthorizedException e,
                                                         HttpServletRequest request) {
        return respond(ApiError.ACCESS_DENIED, e.getMessage(), request);
    }

    @ExceptionHandler(VideoNotReadyException.class)
    public ResponseEntity<ProblemDetail> handleNotReady(VideoNotReadyException e,
                                                       HttpServletRequest request) {
        ProblemDetail detail = problem(ApiError.CONFLICTING_STATE, e.getMessage(), request);
        // Lets the client tell "still working" from "will never work" without reading prose.
        detail.setProperty(ProblemProperties.CURRENT_STATUS, e.status().name());
        log.warn("conflicting-state on {}: video is {}", request.getRequestURI(), e.status());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
    }

    @ExceptionHandler(ThumbnailUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleNoSprite(ThumbnailUnavailableException e,
                                                        HttpServletRequest request) {
        return respond(ApiError.UNPROCESSABLE, e.getMessage(), request);
    }

    /**
     * An upload that failed verification.
     * <p>
     * 413 for oversized, because the client can act on that by picking a shorter clip. 409 for
     * missing or empty, which means the PUT never completed and should simply be retried.
     */
    @ExceptionHandler(UploadVerificationException.class)
    public ResponseEntity<ProblemDetail> handleUploadVerification(UploadVerificationException e,
                                                                 HttpServletRequest request) {
        HttpStatus status = switch (e.kind()) {
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case MISSING, EMPTY -> HttpStatus.CONFLICT;
        };
        ApiError error = switch (e.kind()) {
            case TOO_LARGE -> ApiError.UPLOAD_TOO_LARGE;
            case MISSING -> ApiError.UPLOAD_MISSING;
            case EMPTY -> ApiError.UPLOAD_MISSING;
        };

        ProblemDetail detail = problem(error, e.getMessage(), request);
        detail.setProperty(ProblemProperties.ACTUAL_BYTES, e.actualBytes());
        detail.setProperty(ProblemProperties.MAX_BYTES, e.maxBytes());

        log.warn("{} on {}: {}", error.wireValue(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(status).body(detail);
    }

    /**
     * A request that reached a handler needing a caller but had none, which means it bypassed the
     * gateway. 403 rather than 500.
     */
    @ExceptionHandler(CallerIdentityArgumentResolver.CallerIdentityNotResolvedException.class)
    public ResponseEntity<ProblemDetail> handleNoCaller(
            CallerIdentityArgumentResolver.CallerIdentityNotResolvedException e,
            HttpServletRequest request) {
        return respond(ApiError.GATEWAY_SECRET_INVALID, e.getMessage(), request);
    }

    /* ----------------------------- request problems ----------------------------- */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<String> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();

        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        org.springframework.validation.FieldError::getDefaultMessage,
                        (first, ignored) -> first));

        ProblemDetail detail = problem(ApiError.VALIDATION_FAILED,
                "One or more fields are invalid.", request);
        detail.setProperty(ProblemProperties.VIOLATIONS, violations);
        detail.setProperty(ProblemProperties.FIELD_ERRORS, fieldErrors);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        // The parser message names internal classes; a client gains nothing from it.
        return respond(ApiError.MALFORMED_REQUEST,
                "Request body is missing or is not valid JSON.", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException e,
                                                              HttpServletRequest request) {
        return respond(ApiError.MISSING_REQUIRED_VALUE,
                "Required header '" + e.getHeaderName() + "' is missing.", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException e,
                                                                HttpServletRequest request) {
        return respond(ApiError.MISSING_REQUIRED_VALUE,
                "Required parameter '" + e.getParameterName() + "' is missing.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return respond(ApiError.VALIDATION_FAILED,
                "'" + e.getName() + "' has an invalid format.", request);
    }

    /* ------------------------------ routing problems ----------------------------- */

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException e,
                                                          HttpServletRequest request) {
        return respond(ApiError.NOT_FOUND, "No endpoint matches this path.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        ProblemDetail detail = problem(ApiError.METHOD_NOT_ALLOWED,
                "HTTP method not supported for this path.", request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, e.getSupportedMethods() == null
                        ? "" : String.join(", ", e.getSupportedMethods()))
                .body(detail);
    }

    /* ----------------------------- conflict problems ----------------------------- */

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrityViolation(DataIntegrityViolationException e,
                                                                  HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), e);
        return respond(ApiError.CONFLICTING_STATE, "That value is already in use.", request);
    }

    /* --------------------------------- fallback ---------------------------------- */

    /**
     * Anything unhandled gets a fresh trace id, returned to the client and written to the log.
     * The exception message is never returned: it routinely contains SQL, file paths, or
     * attacker-supplied input.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled exception on {} (traceId={})", request.getRequestURI(), traceId, e);

        ProblemDetail detail = problem(ApiError.INTERNAL_ERROR,
                "Something went wrong on our side. Quote this trace id if you report it: " + traceId,
                request);
        detail.setProperty(ProblemProperties.TRACE_ID, traceId);
        return ResponseEntity.status(HttpStatus.valueOf(ApiError.INTERNAL_ERROR.httpStatus()))
                .body(detail);
    }

    /* ---------------------------------- helpers ---------------------------------- */

    private ResponseEntity<ProblemDetail> respond(ApiError error, String detail,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.valueOf(error.httpStatus()))
                .body(problem(error, detail, request));
    }

    private ProblemDetail problem(ApiError error, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(error.httpStatus()),
                detail == null ? error.wireValue() : detail);
        problem.setTitle(error.wireValue());
        problem.setType(URI.create(PROBLEM_BASE + error.wireValue()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty(ProblemProperties.CODE, error.wireValue());
        return problem;
    }
}
