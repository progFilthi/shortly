package com.shortly.transcoderservice.transcode;

import com.shortly.contracts.events.RenditionInfo;
import com.shortly.contracts.events.VideoReadyEvent;
import com.shortly.contracts.media.TranscodeFailureReason;
import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import com.shortly.transcoderservice.media.MediaMetadata;
import com.shortly.transcoderservice.media.MediaProbeService;
import com.shortly.transcoderservice.media.MediaValidationException;
import com.shortly.transcoderservice.media.MediaValidator;
import com.shortly.transcoderservice.media.UnprobeableMediaException;
import com.shortly.transcoderservice.storage.SourceObjectMissingException;
import com.shortly.transcoderservice.storage.StorageException;
import com.shortly.transcoderservice.storage.TranscoderObjectStore;
import com.shortly.transcoderservice.storage.TranscoderStorageProperties;
import com.shortly.transcoderservice.thumbnail.ThumbnailExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The end-to-end job for one video.
 * <p>
 * Order is load-bearing and each step is cheap relative to the next, so the expensive work
 * is always last:
 * <ol>
 *   <li>download the source (~seconds)</li>
 *   <li>probe it (~100ms) - from here on every rejection is free</li>
 *   <li>validate against platform limits (~0ms)</li>
 *   <li>encode the ladder (~minutes)</li>
 *   <li>extract thumbnails (~seconds)</li>
 *   <li>upload, master manifest last</li>
 * </ol>
 * Every failure mode maps to a {@link TranscodeFailureReason} so the client can be told
 * which limit was hit rather than just "something went wrong".
 */
@Component
public class TranscodePipeline {

    private static final Logger log = LoggerFactory.getLogger(TranscodePipeline.class);

    private final TranscoderProperties properties;
    private final TranscoderObjectStore objectStore;
    private final TranscoderStorageProperties storage;
    private final MediaProbeService probeService;
    private final MediaValidator validator;
    private final HlsCommandBuilder commandBuilder;
    private final FfmpegExecutor executor;
    private final HlsUploader uploader;
    private final ThumbnailExtractor thumbnailExtractor;

    public TranscodePipeline(TranscoderProperties properties,
                             TranscoderObjectStore objectStore,
                             TranscoderStorageProperties storage,
                             MediaProbeService probeService,
                             MediaValidator validator,
                             HlsCommandBuilder commandBuilder,
                             FfmpegExecutor executor,
                             HlsUploader uploader,
                             ThumbnailExtractor thumbnailExtractor) {
        this.properties = properties;
        this.objectStore = objectStore;
        this.storage = storage;
        this.probeService = probeService;
        this.validator = validator;
        this.commandBuilder = commandBuilder;
        this.executor = executor;
        this.uploader = uploader;
        this.thumbnailExtractor = thumbnailExtractor;
    }

    public VideoReadyEvent process(UUID videoId, String s3Key, long declaredSizeBytes) {
        long startedAt = System.nanoTime();

        try (JobWorkspace workspace = new JobWorkspace(properties, videoId)) {

            /* ---------------------------- 1. download ---------------------------- */
            objectStore.downloadToFile(s3Key, workspace.sourceFile());
            long actualSizeBytes = workspace.sourceFile().toFile().length();
            log.info("[{}] downloaded source ({} bytes) in {}ms", videoId, actualSizeBytes,
                    elapsedMs(startedAt));

            /*
             * Trust the bytes, not the event. The event's size is a hint produced before the
             * upload was necessarily complete; the local file is what ffprobe is about to
             * read, so validating that is what actually bounds the work.
             */
            if (actualSizeBytes == 0) {
                throw new MediaValidationException(TranscodeFailureReason.SOURCE_CORRUPT,
                        "Source object is empty");
            }
            if (actualSizeBytes > properties.maxUploadBytes().toBytes()) {
                throw new MediaValidationException(TranscodeFailureReason.SIZE_EXCEEDED,
                        "Source is " + actualSizeBytes + " bytes, limit is "
                                + properties.maxUploadBytes().toBytes());
            }

            /* ------------------------------ 2. probe ----------------------------- */
            MediaMetadata metadata = probeService.probe(workspace.sourceFile());
            log.info("[{}] probed: {}x{} (display {}x{}), {}fps, {} codec, duration {}s, audio={}, hdr={}",
                    videoId, metadata.width(), metadata.height(),
                    metadata.displayWidth(), metadata.displayHeight(),
                    Math.round(metadata.frameRate()), metadata.videoCodec(),
                    metadata.duration().toMillis() / 1000.0,
                    metadata.hasAudio() ? metadata.audioCodec() : "none",
                    metadata.isHdr() ? metadata.colorTransfer() : "no");

            /* ----------------------------- 3. validate --------------------------- */
            validator.validate(metadata, actualSizeBytes);
            List<Rendition> rungs = validator.applicableRungs(metadata);
            log.info("[{}] ladder: {} of {} configured rungs apply to this source ({})",
                    videoId, rungs.size(), properties.ladder().size(),
                    rungs.stream().map(Rendition::name).toList());

            /* ------------------------------ 4. encode --------------------------- */
            List<String> command = commandBuilder.build(
                    workspace.sourceFile(), metadata, rungs, workspace.hlsDir());
            log.info("[{}] encoding: {}", videoId, String.join(" ", command));

            FfmpegExecutor.Result result = executor.run(command, properties.jobTimeout());
            if (!result.succeeded()) {
                // ffmpeg's own diagnostics go to the log in full. The exception carries only
                // the first line, because it ends up in a DB column a human may read.
                log.error("[{}] ffmpeg failed for {}: {}",
                        videoId, describeTarget(command), result.stderr());
                throw new TranscodeFailedException(
                        "ffmpeg exited with code " + result.exitCode() + " while producing "
                                + rungs.size() + " rendition(s): " + firstLine(result.stderr()));
            }
            log.info("[{}] ladder encoded in {}ms", videoId, elapsedMs(startedAt));

            /* ---------------------------- 5. thumbnails ------------------------- */
            List<Double> spriteIntervals =
                    thumbnailExtractor.extract(workspace.sourceFile(), metadata, workspace.thumbnailDir());

            /* ------------------------------ 6. upload --------------------------- */
            List<String> renditionNames = rungs.stream().map(Rendition::name).toList();
            List<String> uploadedKeys = new ArrayList<>();
            try {
                // Segments and media playlists first; the master manifest only once every
                // segment it references is durable.
                uploadedKeys.addAll(uploader.upload(videoId, workspace.hlsDir(), renditionNames));
                uploadThumbnails(videoId, workspace, spriteIntervals);
            } catch (RuntimeException e) {
                // Best effort. A partially written ladder is unreachable anyway because the
                // master manifest is uploaded last, so this only saves storage, and a
                // lifecycle rule on the output prefix is the real safety net.
                objectStore.deleteQuietly(cleanupKeys(videoId, renditionNames));
                throw e;
            }

            int durationSeconds = (int) Math.round(metadata.duration().toMillis() / 1000.0);

            VideoReadyEvent ready =
                    new VideoReadyEvent(
                            videoId,
                            null,
                            storage.cdnUrl(storage.manifestKey(videoId)),
                            storage.cdnUrl(storage.posterKey(videoId)),
                            spriteIntervals.isEmpty() ? null : storage.cdnUrl(storage.spriteKey(videoId)),
                            spriteIntervals.size(),
                            properties.spriteColumns(),
                            durationSeconds,
                            rungs.get(0).width(),
                            rungs.get(0).height(),
                            rungs.stream().map(this::toRenditionInfo).toList(),
                            Instant.now());

            // Written last, after the master exists. A ladder without a sidecar is still
            // playable, so this must not be able to fail the job; the listener degrades
            // gracefully if it is missing.
            objectStore.putJson(storage.readyEventKey(videoId), ready);

            log.info("[{}] complete in {}ms ({} rungs, {} objects, {} bytes on disk)",
                    videoId, elapsedMs(startedAt), rungs.size(), uploadedKeys.size(),
                    workspace.sizeOnDisk());

            return ready;
        }
    }

    private void uploadThumbnails(UUID videoId, JobWorkspace workspace, List<Double> intervals) {
        Path poster = workspace.thumbnailDir().resolve("poster.jpg");
        if (Files.isRegularFile(poster)) {
            objectStore.put(storage.posterKey(videoId), poster,
                    storage.imageContentType(), storage.outputCacheControl());
        }
        if (!intervals.isEmpty()) {
            Path sprite = workspace.thumbnailDir().resolve("sprite.jpg");
            if (Files.isRegularFile(sprite)) {
                objectStore.put(storage.spriteKey(videoId), sprite,
                        storage.imageContentType(), storage.outputCacheControl());
            }
        }
    }

    private List<String> cleanupKeys(UUID videoId, List<String> renditionNames) {
        List<String> keys = new ArrayList<>();
        keys.addAll(objectStore.collectOutputKeys(videoId, renditionNames));
        keys.add(storage.posterKey(videoId));
        keys.add(storage.spriteKey(videoId));
        keys.add(storage.readyEventKey(videoId));
        return keys;
    }

    private RenditionInfo toRenditionInfo(Rendition rendition) {
        return new RenditionInfo(
                rendition.name(),
                rendition.width(),
                rendition.height(),
                rendition.videoKbps(),
                Integer.parseInt(properties.audioBitrate().replaceAll("[^0-9]", "")));
    }
    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    /** Identifies the source being encoded, so an ffmpeg error names something recognisable. */
    private String describeTarget(List<String> command) {
        for (String arg : command) {
            if (arg.endsWith(".mp4") || arg.endsWith(".mov") || arg.endsWith(".m4v")) {
                return Path.of(arg).getFileName().toString();
            }
        }
        return "source";
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "no diagnostic output";
        }
        int newline = text.indexOf('\n');
        return newline > 0 ? text.substring(0, newline) : text;
    }

    /* ------------------------- failure classification ------------------------- */

    /** Maps a pipeline exception to the reason published in {@code VideoFailedEvent}. */
    public static TranscodeFailureReason classify(Throwable error) {
        return switch (error) {
            case MediaValidationException e -> e.reason();
            case SourceObjectMissingException ignored -> TranscodeFailureReason.SOURCE_MISSING;
            case UnprobeableMediaException ignored -> TranscodeFailureReason.SOURCE_CORRUPT;
            case FfmpegTimeoutException ignored -> TranscodeFailureReason.TIMEOUT;
            case StorageException ignored -> TranscodeFailureReason.UPLOAD_FAILED;
            case TranscodeFailedException ignored -> TranscodeFailureReason.TRANSCODE_FAILED;
            default -> TranscodeFailureReason.TRANSCODE_FAILED;
        };
    }
}
