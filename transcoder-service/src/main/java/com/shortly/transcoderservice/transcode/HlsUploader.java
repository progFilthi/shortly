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

/**
 * Uploads a locally-produced ladder to object storage.
 * <p>
 * Two decisions here carry the weight:
 * <p>
 * <b>Concurrency.</b> A 6-rung, 60-second ladder is 187 objects. Issued sequentially over a
 * blocking client that is hundreds of round trips of pure latency, which would dominate the
 * job's wall time for no benefit. Issued concurrently it is a few hundred milliseconds.
 * <p>
 * <b>Manifests last.</b> {@code master.m3u8} is uploaded only after every segment and every
 * media playlist is durable. This is the correctness mechanism for the whole pipeline: until
 * the master exists, a player handed the URL gets a 404 and nothing can serve a ladder that
 * is 40% uploaded. It also means an interrupted job leaves unreachable objects rather than a
 * playable-but-broken one, so a lifecycle rule on the output prefix is enough to reap them.
 */
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

    /**
     * Uploads every artifact for one job and returns the keys it wrote, so the caller can
     * clean them up if a later step fails.
     *
     * @throws StorageException if any artifact fails to upload
     */
    public List<String> upload(UUID videoId, Path hlsDir, List<String> renditionNames) {
        List<Upload> segments = new ArrayList<>();
        List<Upload> mediaPlaylists = new ArrayList<>();
        Upload manifest = null;

        for (String rendition : renditionNames) {
            Path renditionDir = hlsDir.resolve(rendition);
            if (!Files.isDirectory(renditionDir)) {
                throw new StorageException(
                        "Expected rendition directory " + renditionDir + " does not exist;"
                                + " ffmpeg reported success but produced no output");
            }

            Path playlist = renditionDir.resolve("index.m3u8");
            if (!Files.isRegularFile(playlist)) {
                throw new StorageException("Missing media playlist " + playlist);
            }
            mediaPlaylists.add(new Upload(
                    storage.variantPlaylistKey(videoId, rendition), playlist,
                    storage.playlistContentType(), storage.playlistCacheControl()));

            for (Path segment : listSegments(renditionDir)) {
                segments.add(new Upload(
                        storage.segmentKey(videoId, rendition, segment.getFileName().toString()),
                        segment,
                        storage.segmentContentType(), storage.outputCacheControl()));
            }
        }

        Path manifestPath = hlsDir.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("ffmpeg did not produce " + manifestPath);
        }
        manifest = new Upload(
                storage.manifestKey(videoId), manifestPath,
                storage.playlistContentType(), storage.playlistCacheControl());

        log.info("Uploading ladder for {}: {} segment(s), {} media playlist(s), 1 master manifest",
                videoId, segments.size(), mediaPlaylists.size());

        // Segments and media playlists in parallel, bounded. Then the master, alone, once
        // everything it references is durable.
        List<String> keys = new ArrayList<>();
        keys.addAll(uploadAll(segments));
        keys.addAll(uploadAll(mediaPlaylists));
        uploadAll(List.of(manifest)).forEach(keys::add);

        log.info("Ladder for {} fully uploaded ({} objects)", videoId, keys.size());
        return List.copyOf(keys);
    }

    /**
     * Fans out with a bounded number of in-flight requests.
     * <p>
     * The bound is the point. All 187 requests issued at once would exhaust the connection
     * pool, the file-descriptor table and the heap holding the file bodies, and the resulting
     * failure is far harder to diagnose than a slightly slower upload. A semaphore lets every
     * future start immediately - so there is no head-of-line blocking - while only
     * {@code uploadConcurrency} of them are actually waiting on the network.
     */
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
                        // Never upload ffmpeg's own scratch files. -hls_flags temp_file means a
                        // killed job leaves .tmp files behind, and publishing one would give
                        // a player a segment it cannot parse.
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
