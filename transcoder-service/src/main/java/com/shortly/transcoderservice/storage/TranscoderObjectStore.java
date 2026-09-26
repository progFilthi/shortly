package com.shortly.transcoderservice.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Object storage access for the worker.
 *
 * <p>Not a generic S3 wrapper: this is the only writer of the processed output tree and needs
 * six operations. Anything broader should be argued for on its own merits.
 */
@Component
public class TranscoderObjectStore {

    private static final Logger log = LoggerFactory.getLogger(TranscoderObjectStore.class);

    private final S3Client syncClient;
    private final S3AsyncClient asyncClient;
    private final TranscoderStorageProperties properties;
    private final ObjectMapper objectMapper;

    public TranscoderObjectStore(S3Client syncClient,
                                 S3AsyncClient asyncClient,
                                 TranscoderStorageProperties properties,
                                 ObjectMapper objectMapper) {
        this.syncClient = syncClient;
        this.asyncClient = asyncClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /* --------------------------------- reading --------------------------------- */

    /**
     * Streams an object to a local file. Used to pull the uploaded original into the
     * container's ephemeral disk, because ffmpeg needs a seekable local file - it cannot
     * read an S3 stream efficiently, and piping through stdin prevents the two-pass and
     * thumbnail work this pipeline does.
     */
    public void downloadToFile(String key, Path destination) {
        try (InputStream in = syncClient.getObject(
                GetObjectRequest.builder().bucket(properties.bucketName()).key(key).build())) {

            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, destination);

        } catch (NoSuchKeyException e) {
            throw new SourceObjectMissingException(key, e);
        } catch (IOException e) {
            throw new StorageException("Failed to download s3://" + properties.bucketName() + "/" + key, e);
        }
    }

    public long contentLength(String key) {
        return head(key).contentLength();
    }

    public HeadObjectResponse head(String key) {
        try {
            return syncClient.headObject(
                    HeadObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new SourceObjectMissingException(key, e);
            }
            throw new StorageException("HEAD failed for s3://" + properties.bucketName() + "/" + key, e);
        }
    }

    public boolean exists(String key) {
        try {
            syncClient.headObject(
                    HeadObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("HEAD failed for s3://" + properties.bucketName() + "/" + key, e);
        }
    }

    /**
     * Reads a small JSON sidecar into a contract type.
     *
     * <p>Empty rather than throwing when absent - the caller's fallback for a missing sidecar is
     * a degraded event, which beats re-running the job.
     */
    public <T> java.util.Optional<T> readSidecar(String key, Class<T> type) {
        try (InputStream in = syncClient.getObject(
                GetObjectRequest.builder().bucket(properties.bucketName()).key(key).build())) {
            return java.util.Optional.ofNullable(objectMapper.readValue(in.readAllBytes(), type));
        } catch (NoSuchKeyException e) {
            return java.util.Optional.empty();
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Could not read sidecar " + key, e);
        }
    }

    /** Uploads a JSON sidecar, used for the idempotency record. */
    public void putJson(String key, Object value) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(value);
            syncClient.putObject(PutObjectRequest.builder()
                    .bucket(properties.bucketName())
                    .key(key)
                    .contentType("application/json")
                    .cacheControl(properties.outputCacheControl())
                    .contentLength((long) body.length)
                    .build(),
                    RequestBody.fromBytes(body));
        } catch (RuntimeException e) {
            throw new StorageException("Could not write sidecar " + key, e);
        }
    }

    /* --------------------------------- writing --------------------------------- */

    /**
     * Uploads one generated artifact asynchronously. Content type and cache control are set
     * explicitly rather than left to the SDK's default, because a manifest served as
     * {@code application/octet-stream} or a segment served without cache headers will
     * quietly break ABR or force a re-download on every view.
     */
    public CompletableFuture<Void> putAsync(String key, Path file, String contentType, String cacheControl) {
        long size = file.toFile().length();
        return asyncClient.putObject(
                        PutObjectRequest.builder()
                                .bucket(properties.bucketName())
                                .key(key)
                                .contentType(contentType)
                                .cacheControl(cacheControl)
                                .contentLength(size)
                                .build(),
                        AsyncRequestBody.fromFile(file))
                .thenAccept(response -> log.debug("Uploaded {} ({} bytes)", key, size));
    }

    public void put(String key, Path file, String contentType, String cacheControl) {
        putAsync(key, file, contentType, cacheControl).join();
    }

    /**
     * Best-effort cleanup of a partially produced ladder.
     * <p>
     * Called when a job fails after some artifacts have already been uploaded. The failure
     * path must never mask the original error, so every failure here is logged and
     * swallowed - and note this is a convenience, not a correctness mechanism: correctness
     * comes from uploading the master manifest LAST, so a half-built ladder is never
     * reachable by a player even if this cleanup is interrupted.
     */
    public void deleteQuietly(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (int i = 0; i < keys.size(); i += 1000) {
            List<String> batch = keys.subList(i, Math.min(i + 1000, keys.size()));
            try {
                DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                        .bucket(properties.bucketName())
                        .delete(obj -> obj.objects(batch.stream()
                                .map(k -> ObjectIdentifier.builder().key(k).build())
                                .toList()))
                        .build();
                syncClient.deleteObjects(request);
                log.info("Cleaned up {} orphaned artifact(s) after a failed job", batch.size());
            } catch (RuntimeException e) {
                log.warn("Best-effort cleanup of {} orphaned artifact(s) failed; "
                        + "a lifecycle rule on the output prefix will collect them", batch.size(), e);
            }
        }
    }

    public void deleteQuietly(String key) {
        deleteQuietly(List.of(key));
    }

    /* --------------------------------- copying --------------------------------- */

    /**
     * Server-side copy, used to publish a poster frame without round-tripping bytes through
     * the worker. Same-region S3 copies are free.
     */
    public void copyWithinBucket(String sourceKey, String destinationKey) {
        syncClient.copyObject(CopyObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(destinationKey)
                .copySource(properties.bucketName() + "/" + sourceKey)
                .contentType(properties.imageContentType())
                .cacheControl(properties.outputCacheControl())
                .build());
    }

    /** Builds the list of keys a job intends to write, for the failure-path cleanup. */
    public List<String> collectOutputKeys(java.util.UUID videoId, List<String> renditionNames) {
        List<String> keys = new ArrayList<>();
        for (String rendition : renditionNames) {
            keys.add(properties.variantPlaylistKey(videoId, rendition));
        }
        keys.add(properties.manifestKey(videoId));
        return keys;
    }

    /* --------------------------------- deletes --------------------------------- */

    public void deleteObject(String key) {
        syncClient.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(key)
                .build());
    }

    public TranscoderStorageProperties properties() {
        return properties;
    }
}
