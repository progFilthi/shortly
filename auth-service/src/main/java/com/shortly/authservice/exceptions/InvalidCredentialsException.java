package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

import java.util.Map;

/**
 * The supplied username/email and password did not match a user.
 *
 * <p>Deliberately does not distinguish "no such user" from "wrong password". Distinguishing them
 * turns login into an account-enumeration oracle: an attacker discovers which emails are
 * registered by comparing responses or timings. The client is told only that the credentials
 * were not accepted.
 */
public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super(ApiError.INVALID_CREDENTIALS, "Invalid username or password.");
    }
}
