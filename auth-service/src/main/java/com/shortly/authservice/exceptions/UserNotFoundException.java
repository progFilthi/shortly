package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/** No user for the authenticated id. Normally means the account was deleted while a token for it
 * was still live, which is a real and correct 404. */
public class UserNotFoundException extends AuthException {

    public UserNotFoundException() {
        super(ApiError.NOT_FOUND, "No user found for the authenticated identity.");
    }
}
