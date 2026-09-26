package com.shortly.transcoderservice.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.UUID;

/**
 * Object storage settings for the transcoder.
 * <p>
 * Distinct from video-service's {@code aws.*} block on purpose: the transcoder is a
 * read-heavy writer with different needs (segment concurrency, immutable output keys), and
 * sharing one config block across services would mean both change together.
 */
@ConfigurationProperties(prefix = "transcoder.storage")
public record TranscoderStorageProperties(

        @DefaultValue("shortly-videos-bucket") String bucketName,
        @DefaultValue("https://d21nfbs2lixxr1.cloudfront.net") String cdnDomain,

        /**
         * Output prefix. Keys are addressed as {@code <prefix>/<videoId>/...} with no user
         * segment: content is immutable per videoId, and keeping the user out of the output
         * path means a single video's ladder is shared by every viewer through the CDN
         * rather than being duplicated per user.
         */
        @DefaultValue("hls") String outputPrefix,

        @DefaultValue("b") String segmentContentType,
        @DefaultValue("application/vnd.apple.mpegurl") String playlistContentType,
        @DefaultValue("image/jpeg") String imageContentType,

        /**
         * Objects under the output prefix are immutable and content-addressed by videoId,
         * so they can be cached essentially forever.
         */
        @DefaultValue("public, max-age=31536000, immutable") String outputCacheControl,
        @DefaultValue("no-cache") String playlistCacheControl
) {

    public String manifestKey(UUID videoId) {
        return outputPrefix + "/" + videoId + "/master.m3u8";
    }

    public String variantPlaylistKey(UUID videoId, String rendition) {
        return outputPrefix + "/" + videoId + "/" + rendition + "/index.m3u8";
    }

    public String segmentKey(UUID videoId, String rendition, String filename) {
        return outputPrefix + "/" + videoId + "/" + rendition + "/" + filename;
    }

    public String posterKey(UUID videoId) {
        return outputPrefix + "/" + videoId + "/poster.jpg";
    }

    public String spriteKey(UUID videoId) {
        return outputPrefix + "/" + videoId + "/sprite.jpg";
    }

    /**
     * Sidecar holding the exact {@code VideoReadyEvent} a job published.
     * <p>
     * Written after the master manifest. Its only job is to make the pipeline idempotent: if
     * the ladder exists but the ready event was lost, the listener replays the real payload
     * instead of re-encoding or republishing a guessed one.
     */
    public String readyEventKey(UUID videoId) {
        return outputPrefix + "/" + videoId + "/ready.json";
    }

    public String cdnUrl(String key) {
        String base = cdnDomain.endsWith("/")
                ? cdnDomain.substring(0, cdnDomain.length() - 1)
                : cdnDomain;
        return base + "/" + key;
    }
}
