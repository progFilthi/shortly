package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/** The access token's {@code exp} has passed. */
public class AccessTokenExpiredException extends AuthException {

    public AccessTokenExpiredException() {
        super(ApiError.TOKEN_EXPIRED, "Access token has expired.");
    }
}
