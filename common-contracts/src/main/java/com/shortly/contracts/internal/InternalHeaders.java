package com.shortly.contracts.internal;

/**
 * The header protocol between the gateway and the services behind it.
 *
 * <p>Defined here because both ends must agree exactly. Declaring the names in a shared contract
 * rather than duplicating string literals is what stops a rename in the gateway from silently
 * becoming "authorization is off" in a service: with a shared constant the change is a compile
 * error on both sides instead.
 */
public final class InternalHeaders {

    /** Subject of the verified access token. Set by the gateway, never trusted from the caller. */
    public static final String USER_ID = "X-User-Id";

    /** Username claim, forwarded so services need no user lookup. */
    public static final String USERNAME = "X-Username";

    /** Email claim, forwarded for the same reason. */
    public static final String EMAIL = "X-Email";

    /**
     * Shared secret proving the request was routed by the gateway.
     * <p>
     * Without it, any caller that can reach a service's port directly can set {@link #USER_ID} to
     * anyone and bypass authorization. This is the check that makes the gateway the only way in.
     */
    public static final String GATEWAY_SECRET = "X-Gateway-Secret";

    private InternalHeaders() {
    }
}
