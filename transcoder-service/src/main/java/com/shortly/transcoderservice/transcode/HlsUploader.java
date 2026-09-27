package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.storage.StorageException;
import com.shortly.transcoderservice.storage.TranscoderObjectStore;
import com.shortly.transcoderservice.storage.TranscoderStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Uploads a locally-produced ladder to object storage. Manifests last. {@code master.m3u8} is
 * uploaded only after every segment and every media playlist is durable. */
@Component
public class HlsUploader {

    private static final Logger log = LoggerFactory.getLogger(HlsUploader.class);

    private static final String MANIFEST_NAME = "master.m3u8";

    private final TranscoderObjectStore objectStore;
    private final TranscoderStorageProperties storage;
    private final TranscoderProperties properties;

    public HlsUploader(TranscoderObjectStore objectStore,
                       TranscoderStorageProperties storage,
                       TranscoderProperties properties) {
        this.objectStore = objectStore;
        this.storage = storage;
        this.properties = properties;
    }

    /** Uploads every artifact for one job and returns the keys it wrote, so the caller can clean them
     * up if a later step fails. @throws StorageException if any artifact fails to upload
     *
     * <p>Directories and S3 keys are addressed by the rendition's <em>position</em> in
     * {@code renditionNames}, not by its configured name. {@code HlsCommandBuilder} emits
     * {@code v%v}, which ffmpeg expands to the variant index within <em>this</em> invocation, and
     * {@code master.m3u8} references its variants by those relative paths. When
     * {@code MediaValidator#applicableRungs} drops rungs that would upscale, the applicable ladder is
     * shorter than the configured one and the indices no longer coincide with the names: a 720x1280
     * source keeps rungs {@code v3 v4 v5} but ffmpeg writes {@code v0 v1 v2}. Addressing by name
     * therefore looks for a directory ffmpeg never created, and the job fails after a successful
     * encode. The names are still used for diagnostics. */
    public List<String> upload(UUID videoId, Path hlsDir, List<String> renditionNames) {
        List<Upload> segments = new ArrayList<>();
        List<Upload> mediaPlaylists = new ArrayList<>();

        for (int index = 0; index < renditionNames.size(); index++) {
            String rungName = renditionNames.get(index);
            String variant = variantDirName(index);

            Path renditionDir = hlsDir.resolve(variant);
            if (!Files.isDirectory(renditionDir)) {
                throw new StorageException(
                        "Expected rendition directory " + renditionDir + " does not exist;"
                                + " ffmpeg reported success but produced no output"
                                + " (rung " + rungName + " is variant " + index + " of "
                                + renditionNames.size() + ")");
            }

            Path playlist = renditionDir.resolve("index.m3u8");
            if (!Files.isRegularFile(playlist)) {
                throw new StorageException("Missing media playlist " + playlist);
            }
            mediaPlaylists.add(new Upload(
                    storage.variantPlaylistKey(videoId, variant), playlist,
                    storage.playlistContentType(), storage.playlistCacheControl()));

            for (Path segment : listSegments(renditionDir)) {
                segments.add(new Upload(
                        storage.segmentKey(videoId, variant, segment.getFileName().toString()),
                        segment,
                        storage.segmentContentType(), storage.outputCacheControl()));
            }
        }

        Path manifestPath = hlsDir.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("ffmpeg did not produce " + manifestPath);
        }
        Upload manifest = new Upload(
                storage.manifestKey(videoId), manifestPath,
                storage.playlistContentType(), storage.playlistCacheControl());

        log.info("Uploading ladder for {}: {} segment(s), {} media playlist(s), 1 master manifest",
                videoId, segments.size(), mediaPlaylists.size());

        // Segments and media playlists in parallel, bounded. Then the master, alone, once everything it
        // references is durable.
        List<String> keys = new ArrayList<>();
        keys.addAll(uploadAll(segments));
        keys.addAll(uploadAll(mediaPlaylists));
        uploadAll(List.of(manifest)).forEach(keys::add);

        log.info("Ladder for {} fully uploaded ({} objects)", videoId, keys.size());
        return List.copyOf(keys);
    }

    /** The directory name ffmpeg gives variant {@code index}, expanding the {@code v%v} pattern in
     * the command builder. Must stay in step with {@code addHlsMuxerOptions}. */
    private static String variantDirName(int index) {
        return "v" + index;
    }

    /** Fans out with a bounded number of in-flight requests. */
    private List<String> uploadAll(List<Upload> uploads) {
        if (uploads.isEmpty()) {
            return List.of();
        }

        Semaphore permits = new Semaphore(properties.uploadConcurrency());
        List<CompletableFuture<Void>> futures = new ArrayList<>(uploads.size());

        for (Upload upload : uploads) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> {
                        acquire(permits);
                        return upload;
                    })
                    .thenCompose(permit -> objectStore
                            .putAsync(permit.key(), permit.file(),
                                    permit.contentType(), permit.cacheControl())
                            .whenComplete((ignored, error) -> permits.release())));
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(properties.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            throw new StorageException("Interrupted while uploading ladder", e);
        } catch (TimeoutException e) {
            cancelAll(futures);
            throw new StorageException(
                    "Uploading " + uploads.size() + " object(s) exceeded "
                            + properties.uploadTimeout().toSeconds() + "s", e);
        } catch (ExecutionException e) {
            cancelAll(futures);
            throw new StorageException("Failed to upload ladder artifacts", e.getCause());
        }

        return uploads.stream().map(Upload::key).toList();
    }

    private static void acquire(Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while waiting to upload", e);
        }
    }

    private void cancelAll(List<CompletableFuture<Void>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private List<Path> listSegments(Path renditionDir) {
        try (Stream<Path> files = Files.list(renditionDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        // Never upload ffmpeg's own scratch files. -hls_flags temp_file means a killed job leaves .
                        return !name.endsWith(".tmp") && !name.equals("index.m3u8");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list segments in " + renditionDir, e);
        }
    }

    private record Upload(String key, Path file, String contentType, String cacheControl) {
    }
}
