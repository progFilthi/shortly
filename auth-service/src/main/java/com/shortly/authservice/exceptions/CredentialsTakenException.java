package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

/**
 * Username or email is already registered.
 *
 * <p>Raised from the database's unique-violation report as well as from a pre-check, because a
 * pre-check alone races: two concurrent registrations of the same email can both pass it.
 */
public class CredentialsTakenException extends AuthException {

    public CredentialsTakenException() {
        super(ApiError.USERNAME_OR_EMAIL_TAKEN, "That username or email is already registered.");
    }
}
