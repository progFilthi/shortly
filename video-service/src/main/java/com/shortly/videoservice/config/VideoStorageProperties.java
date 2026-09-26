package com.shortly.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Storage and upload-limit configuration.
 * <p>
 * These limits are duplicated in transcoder-service on purpose. video-service enforces them at
 * the upload boundary, where rejecting a file is cheap; the transcoder re-enforces them after
 * probing, because the only trustworthy measurement of a video's duration and dimensions is
 * the one taken from its bytes.
 */
@ConfigurationProperties(prefix = "video")
public record VideoStorageProperties(

        @DefaultValue("shortly-videos-bucket") String bucketName,
        @DefaultValue("https://d21nfbs2lixxr1.cloudfront.net") String cdnDomain,
        @DefaultValue("raw") String sourcePrefix,
        @DefaultValue("hls") String processedPrefix,

        /**
         * Hard ceiling on an uploaded original. Generous on purpose: the intended path is that
         * the client normalises on device and uploads 15-40 MB, so this only has to accommodate
         * the fallback where it does not. Enforced here at {@code /complete} and again in the
         * transcoder after probing.
         */
        @DefaultValue("500MB") DataSize maxUploadBytes,

        /** Advertised to the client so it can reject an over-long clip before uploading it. */
        @DefaultValue("60s") Duration maxDuration,

        @DefaultValue("15m") Duration presignTtl,

        /**
         * Whether to tell clients the platform will transcode anything ffmpeg can decode, or to
         * ask for H.264 specifically. True is strongly preferred: normalising on device cuts
         * upload size by roughly 5-10x and removes an entire class of server-side decode risk.
         */
        @DefaultValue("true") boolean preferH264FromClients
) {

    public String sourceKey(String userId, java.util.UUID videoId, String extension) {
        return sourcePrefix + "/" + userId + "/" + videoId + extension;
    }

    public long maxUploadBytesValue() {
        return maxUploadBytes.toBytes();
    }

    public int maxDurationSeconds() {
        return (int) maxDuration.toSeconds();
    }
}
