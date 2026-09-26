package com.shortly.authservice.security;

/**
 * The authenticated caller, as a typed value.
 *
 * <p>Preferred over a bare {@code String} userId so a missing identity is a type error at the
 * point of use rather than a null passed three layers down.
 *
 * @param userId   subject of the verified access token
 * @param username username claim, forwarded by the gateway
 * @param email    email claim, forwarded by the gateway
 */
public record AuthenticatedUser(String userId, String username, String email) {

    /** Request attribute the authentication filter publishes the value under. */
    public static final String REQUEST_ATTRIBUTE = "shortly.authenticatedUser";

    public boolean isPresent() {
        return userId != null && !userId.isBlank();
    }
}
