package com.shortly.jwt;

/**
 * Claim names and token kinds.
 *
 * <p>Shared so the issuer and the verifier cannot disagree about what a token is. The
 * {@link #TOKEN_TYPE} claim is the important one: it is what stops a token minted for one purpose
 * being replayed as another.
 */
public final class JwtClaims {

    /** Marks a token as an access token. Checked on every verification. */
    public static final String TOKEN_TYPE = "typ";

    public static final String USERNAME = "username";
    public static final String EMAIL = "email";

    public static final String TYPE_ACCESS = "access";

    private JwtClaims() {
    }
}
