package com.shortly.contracts.events;

import java.util.List;
import java.util.UUID;

/** One rung of a produced HLS ladder, as published to clients. */
public record RenditionInfo(
        String name,
        int width,
        int height,
        int videoKbps,
        int audioKbps
) {
}
