package com.shortly.apigateway.errors;

import com.shortly.contracts.errors.ApiError;
import com.shortly.contracts.errors.ProblemProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.UUID;

/**
 * Gateway-level error handling.
 *
 * <p>The gateway has no controllers, so this handles a narrow set: routing failures the gateway
 * itself raises, and anything the framework throws before a request reaches a service. Most
 * rejections originate in {@code JwtAuthenticationFilter} and are written there directly, because
 * a filter runs before this advice.
 *
 * <p>Uses the same {@link ApiError} codes as the services, so a client branches on the code and
 * does not need to know which hop answered.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://shortly.dev/problems/";

    /** No route matched. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoRoute(NoResourceFoundException e,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(ApiError.NOT_FOUND, "No endpoint matches this path.", request));
    }

    /** The route exists but not for this method. */
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

    /**
     * A downstream service is unreachable, or timed out.
     * <p>
     * 503 rather than 500, because this is the one gateway failure that is genuinely worth
     * retrying, and a client that cannot tell the difference will eventually stop retrying the
     * cases where it should.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException e,
                                                              HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        ApiError error = status.value() == 503 ? ApiError.SERVICE_UNAVAILABLE
                : status.value() == 404 ? ApiError.NOT_FOUND
                : ApiError.INTERNAL_ERROR;
        return ResponseEntity.status(status).body(problem(error, e.getReason(), request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(ApiError.MALFORMED_REQUEST,
                        "Request body is missing or is not valid JSON.", request));
    }

    /**
     * Anything else.
     * <p>
     * Returns a trace id rather than the message, because a gateway exception can carry upstream
     * connection details that have no business reaching a client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled gateway error on {} (traceId={})", request.getRequestURI(), traceId, e);

        ProblemDetail detail = problem(ApiError.INTERNAL_ERROR,
                "The gateway could not process this request. Trace id: " + traceId, request);
        detail.setProperty(ProblemProperties.TRACE_ID, traceId);
        return ResponseEntity.status(HttpStatus.valueOf(ApiError.INTERNAL_ERROR.httpStatus()))
                .body(detail);
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
