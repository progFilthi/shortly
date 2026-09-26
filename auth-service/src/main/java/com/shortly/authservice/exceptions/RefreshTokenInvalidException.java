package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/**
 * A refresh token was unknown, expired, already rotated, or explicitly revoked.
 *
 * <p>One exception and one message for all four cases, deliberately. Telling a caller "this
 * token was already used" confirms the token was genuine, which is leverage for anyone holding a
 * stolen token pair. The client only needs to know it must sign in again; the specific cause goes
 * to the log.
 */
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
