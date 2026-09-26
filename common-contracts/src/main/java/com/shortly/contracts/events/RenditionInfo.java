package com.shortly.contracts.events;

import java.util.List;
import java.util.UUID;

/**
 * One rung of a produced HLS ladder, as published to clients.
 *
 * @param name       stable rung identifier, also the directory name in object storage
 * @param width      pixel width after the canonical aspect transform
 * @param height     pixel height after the canonical aspect transform
 * @param videoKbps  target video bitrate for this rung
 * @param audioKbps  constant audio bitrate carried by every rung
 */
public record RenditionInfo(
        String name,
        int width,
        int height,
        int videoKbps,
        int audioKbps
) {
}
