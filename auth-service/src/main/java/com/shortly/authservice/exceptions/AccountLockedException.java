package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;
import com.shortly.contracts.errors.ProblemProperties;

import java.util.Map;

/** Too many consecutive failed sign-ins; the account is temporarily locked. */
public class AccountLockedException extends AuthException {

    private final long retryAfterSeconds;

    public AccountLockedException(long retryAfterSeconds) {
        super(ApiError.ACCOUNT_LOCKED,
                "Account is temporarily locked. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Map<String, Object> problemDetails() {
        return Map.of(ProblemProperties.RETRY_AFTER_SECONDS, retryAfterSeconds);
    }
}
