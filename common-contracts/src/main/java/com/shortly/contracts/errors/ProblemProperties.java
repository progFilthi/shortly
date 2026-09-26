package com.shortly.contracts.errors;

/**
 * Standard properties attached to every problem response, beyond the RFC 9457 core fields.
 *
 * <p>A stable vocabulary so a client can read {@code problem.getProperties()[TRACE_ID]} without
 * knowing which service answered.
 */
public final class ProblemProperties {

    /** Machine-readable error code. Matches {@link ApiError#wireValue()}. */
    public static final String CODE = "code";

    /** Correlates a client-visible failure with a server log line. */
    public static final String TRACE_ID = "traceId";

    /** Per-field validation messages. Present on {@link ApiError#VALIDATION_FAILED}. */
    public static final String VIOLATIONS = "violations";

    /** Field-by-field detail for form-style failures. */
    public static final String FIELD_ERRORS = "fieldErrors";

    /** Actual and permitted sizes. Present on {@link ApiError#UPLOAD_TOO_LARGE}. */
    public static final String ACTUAL_BYTES = "actualBytes";
    public static final String MAX_BYTES = "maxBytes";

    /** Seconds to wait. Present on {@link ApiError#ACCOUNT_LOCKED}. */
    public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";

    /** Current aggregate state. Present on {@link ApiError#CONFLICTING_STATE}. */
    public static final String CURRENT_STATUS = "currentStatus";

    private ProblemProperties() {
    }
}
