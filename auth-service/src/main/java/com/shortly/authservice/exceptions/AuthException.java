package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Map;

/** Base for auth-service failures, so one handler can translate them. */
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

    /** Extra problem properties to merge in, for the codes that carry structured detail. */
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
