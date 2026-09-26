package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Map;

/**
 * Base for auth-service failures, so one handler can translate them.
 *
 * <p>Each subclass declares its {@link ApiError} and whether it is retryable. The handler reads
 * those two facts instead of a growing chain of {@code instanceof} checks.
 */
public abstract class AuthException extends RuntimeException {

    private final ApiError error;

    protected AuthException(ApiError error, String message) {
        super(message);
        this.error = error;
    }

    protected AuthException(ApiError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ApiError error() {
        return error;
    }

    /**
     * Extra problem properties to merge in, for the codes that carry structured detail.
     * Default is none.
     */
    public Map<String, Object> problemDetails() {
        return Map.of();
    }

    /** Convenience for building a problem response without a handler. */
    public ProblemDetail toProblemDetail() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(error.httpStatus()), getMessage());
        detail.setTitle(error.wireValue());
        detail.setProperty("code", error.wireValue());
        problemDetails().forEach(detail::setProperty);
        return detail;
    }
}
