package com.shortly.authservice.security;

/** The authenticated caller, as a typed value. */
public record AuthenticatedUser(String userId, String username, String email) {

    /** Request attribute the authentication filter publishes the value under. */
    public static final String REQUEST_ATTRIBUTE = "shortly.authenticatedUser";

    public boolean isPresent() {
        return userId != null && !userId.isBlank();
    }
}
