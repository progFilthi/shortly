package com.shortly.contracts.errors;

/**
 * Canonical error codes and their HTTP statuses, shared by every service.
 *
 * <p>The point is that a client can branch on {@code code} without special-casing which service
 * answered. A 401 from the gateway and a 401 from auth-service both report
 * {@code TOKEN_EXPIRED}, and an oversized upload reports {@code UPLOAD_TOO_LARGE} rather than a
 * free-text message that would have to be pattern-matched.
 *
 * <p>HTTP status is carried as a plain int so this module stays framework-free. A service's
 * handler reads {@link #httpStatus()} and builds a {@code ProblemDetail} from it.
 *
 * <p>Adding a code is safe. Renaming or repurposing one is a breaking API change, because
 * clients are expected to switch on it.
 */
public enum ApiError {

    /* ------------------------------- 400 Bad Request ------------------------------ */

    /** Request body or parameters failed bean validation. Carries a per-field list. */
    VALIDATION_FAILED(400),
    /** Body was not parseable as the expected type. */
    MALFORMED_REQUEST(400),
    /** A required header or parameter was absent. */
    MISSING_REQUIRED_VALUE(400),
    /** The path does not match any route. */
    NOT_FOUND(404),
    /** The path exists but not for this HTTP method. */
    METHOD_NOT_ALLOWED(405),

    /* -------------------------------- 401 Unauthorized ----------------------------- */

    /** No credentials presented, or the presented credentials are not valid. */
    UNAUTHENTICATED(401),
    /** Credentials are valid but the access token has expired. Refresh, then retry. */
    TOKEN_EXPIRED(401),
    /** A refresh token was unknown, already used, or revoked. */
    REFRESH_TOKEN_INVALID(401),
    /** Username or password did not match. Deliberately does not say which. */
    INVALID_CREDENTIALS(401),

    /* --------------------------------- 403 Forbidden ------------------------------- */

    /** Authenticated, but not allowed to touch this resource. */
    ACCESS_DENIED(403),
    /** The request did not come through the gateway, so the caller identity is untrusted. */
    GATEWAY_SECRET_INVALID(403),

    /* ----------------------------------- 409 Conflict ------------------------------ */

    /** Username or email is already registered. */
    USERNAME_OR_EMAIL_TAKEN(409),
    /** The resource is not in a state where this operation makes sense. */
    CONFLICTING_STATE(409),
    /** The referenced upload never landed, or landed empty. */
    UPLOAD_MISSING(409),

    /* -------------------------------- 413 Payload Too Large ------------------------- */

    /** Upload exceeds the configured ceiling. Carries {@code actualBytes} and {@code maxBytes}. */
    UPLOAD_TOO_LARGE(413),

    /* --------------------------------- 422 Unprocessable --------------------------- */

    /** Semantically invalid, e.g. picking a cover frame on a video that has no sprite sheet. */
    UNPROCESSABLE(422),

    /* ------------------------- 423 Locked / 429 Too Many Requests ------------------- */

    /** Too many failed sign-in attempts. Carries {@code retryAfterSeconds}. */
    ACCOUNT_LOCKED(423),

    /* ------------------------------- 5xx Server Error ----------------------------- */

    /** Unexpected server fault. Carries a {@code traceId} for log correlation. */
    INTERNAL_ERROR(500),
    /** A downstream dependency is unavailable. Retryable. */
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    ApiError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Stable kebab-case identifier used in the {@code code} property and the problem type URI. */
    public String wireValue() {
        StringBuilder out = new StringBuilder(name().length() + 4);
        for (int i = 0; i < name().length(); i++) {
            char c = name().charAt(i);
            if (c == '_') {
                out.append('-');
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
