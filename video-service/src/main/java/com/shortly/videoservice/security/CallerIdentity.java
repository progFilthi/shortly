package com.shortly.videoservice.security;

/** The gateway-asserted caller. */
public record CallerIdentity(String userId, String username, String email) {

    /** Request attribute the filter publishes this under. */
    public static final String REQUEST_ATTRIBUTE = "shortly.callerIdentity";
}
