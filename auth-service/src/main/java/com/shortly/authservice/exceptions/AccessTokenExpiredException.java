package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/**
 * The access token's {@code exp} has passed. Separate from {@code UNAUTHENTICATED} so a client
 * can tell "refresh me and retry" apart from "sign in again".
 */
public class AccessTokenExpiredException extends AuthException {

    public AccessTokenExpiredException() {
        super(ApiError.TOKEN_EXPIRED, "Access token has expired.");
    }
}
