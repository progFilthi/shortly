package com.shortly.videoservice.security;

/**
 * The gateway-asserted caller.
 *
 * <p>Controllers declare a parameter of this type and get it injected, rather than reading the
 * {@code X-User-Id} header themselves. That is not convenience: the header is only trustworthy
 * because {@link CallerIdentityFilter} verified the gateway secret first, and funneling every read
 * through one place is what makes that check hard to bypass by accident.
 *
 * @param userId   the access token's subject
 * @param username forwarded for display, may be null
 * @param email    forwarded for display, may be null
 */
public record CallerIdentity(String userId, String username, String email) {

    /** Request attribute the filter publishes this under. */
    public static final String REQUEST_ATTRIBUTE = "shortly.callerIdentity";
}
