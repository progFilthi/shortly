package com.shortly.contracts.messaging;

/**
 * Canonical AMQP topology for the video pipeline.
 * <p>
 * Constants only - no topology is declared here. Each service declares the exchanges and
 * queues it needs (producer declares what it publishes, consumer declares what it consumes),
 * which keeps the contract module free of framework dependencies and lets services be
 * deployed independently.
 */
public final class MessagingTopology {

    private MessagingTopology() {
    }

    /* --------------------------------- video ---------------------------------- */

    public static final String VIDEO_EXCHANGE = "video.exchange";

    public static final String VIDEO_UPLOADED_ROUTING_KEY = "video.uploaded";
    public static final String VIDEO_UPLOADED_QUEUE = "video.uploaded.queue";

    public static final String VIDEO_READY_ROUTING_KEY = "video.ready";
    public static final String VIDEO_READY_QUEUE = "video.ready.queue";

    public static final String VIDEO_FAILED_ROUTING_KEY = "video.failed";
    public static final String VIDEO_FAILED_QUEUE = "video.failed.queue";

    /* ---------------------------- dead letter routing -------------------------- */

    /** Dead-letter exchange every work queue points at. */
    public static final String DEAD_LETTER_EXCHANGE = "video.dlx";

    public static final String VIDEO_UPLOADED_DLQ = "video.uploaded.dlq";
    public static final String VIDEO_READY_DLQ = "video.ready.dlq";
    public static final String VIDEO_FAILED_DLQ = "video.failed.dlq";
}
