package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;
import com.shortly.contracts.errors.ProblemProperties;

import java.util.Map;

/** The password is shorter than the configured minimum. */
public class PasswordTooWeakException extends AuthException {

    private final int minimumLength;

    public PasswordTooWeakException(int minimumLength) {
        super(ApiError.VALIDATION_FAILED, "Password must be at least " + minimumLength + " characters.");
        this.minimumLength = minimumLength;
    }

    @Override
    public Map<String, Object> problemDetails() {
        return Map.of(
                ProblemProperties.FIELD_ERRORS,
                Map.of("password", "Must be at least " + minimumLength + " characters"),
                "minLength", minimumLength);
    }
}
