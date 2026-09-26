package com.shortly.jwt;

/** Claim names and token kinds. Shared so the issuer and the verifier cannot disagree about what a
 * token is. */
public final class JwtClaims {

    /** Marks a token as an access token. Checked on every verification. */
    public static final String TOKEN_TYPE = "typ";

    public static final String USERNAME = "username";
    public static final String EMAIL = "email";

    public static final String TYPE_ACCESS = "access";

    private JwtClaims() {
    }
}
