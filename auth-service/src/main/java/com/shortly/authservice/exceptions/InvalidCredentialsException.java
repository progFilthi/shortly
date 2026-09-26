package com.shortly.authservice.exceptions;

import com.shortly.contracts.errors.ApiError;

import java.util.Map;

/** The supplied username/email and password did not match a user. Deliberately does not distinguish
 * "no such user" from "wrong password". */
public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super(ApiError.INVALID_CREDENTIALS, "Invalid username or password.");
    }
}
