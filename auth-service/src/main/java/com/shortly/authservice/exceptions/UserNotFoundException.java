package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/**
 * No user for the authenticated id.
 *
 * <p>Normally means the account was deleted while a token for it was still live, which is a real
 * and correct 404. It is a separate type from the video service's not-found because the two
 * services own different aggregates.
 */
public class UserNotFoundException extends AuthException {

    public UserNotFoundException() {
        super(ApiError.NOT_FOUND, "No user found for the authenticated identity.");
    }
}
