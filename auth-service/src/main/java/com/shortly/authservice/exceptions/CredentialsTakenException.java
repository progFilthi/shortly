package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/** Username or email is already registered. Raised from the database's unique-violation report as
 * same email can both pass it. */
public class CredentialsTakenException extends AuthException {

    public CredentialsTakenException() {
        super(ApiError.USERNAME_OR_EMAIL_TAKEN, "That username or email is already registered.");
    }
}
