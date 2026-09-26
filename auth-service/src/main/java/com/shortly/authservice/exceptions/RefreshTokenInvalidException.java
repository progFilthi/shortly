package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/** A refresh token was unknown, expired, already rotated, or explicitly revoked. One exception and
 * one message for all four cases, deliberately. */
public class RefreshTokenInvalidException extends AuthException {

    public RefreshTokenInvalidException() {
        super(ApiError.REFRESH_TOKEN_INVALID,
                "Refresh token is invalid or has expired. Please sign in again.");
    }

    /** Carries a server-side-only reason, logged but never returned. */
    public RefreshTokenInvalidException(String logMessage) {
        super(ApiError.REFRESH_TOKEN_INVALID, logMessage);
    }
}
