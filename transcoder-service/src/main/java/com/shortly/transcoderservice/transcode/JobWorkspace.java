package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Per-job scratch space on the container's local disk.
 * <p>
 * NOT a Spring bean. One instance exists per job, created by {@link TranscodePipeline} inside a
 * try-with-resources so the directory is removed on every exit path. Registering it as a
 * singleton would mean one shared directory for every concurrent job.
 * <p>
 * ffmpeg cannot work efficiently against an S3 stream: the HLS muxer needs to seek and
 * rewrite its own output directory, and the thumbnail pass re-reads the file. So the source
 * is staged locally, encoded locally, and only the finished artifacts go to S3.
 * <p>
 * Sizing: budget for {@code concurrency x (largest source + full ladder)}. At the default
 * 500 MB ceiling, a 6-rung ladder and concurrency 2 that is roughly 1.2 GB, so the container
 * needs a corresponding ephemeral-storage limit. Set {@code workDir} to a tmpfs mount if
 * you would rather have the kernel enforce the ceiling than the application.
 * <p>
 * Every job gets a unique directory. That is what makes the directory safe to delete
 * unconditionally on the way out, even if a previous attempt at the same video crashed
 * without cleaning up after itself.
 */
public class JobWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobWorkspace.class);

    private final Path root;
    private final UUID videoId;
    private final Path jobDir;
    private final Path sourceFile;
    private final Path hlsDir;
    private final Path thumbnailDir;

    public JobWorkspace(TranscoderProperties properties, UUID videoId) {
        this.videoId = videoId;
        this.root = Path.of(properties.workDir());
        this.jobDir = root.resolve("job-" + videoId + "-" + UUID.randomUUID());
        this.sourceFile = jobDir.resolve("source");
        this.hlsDir = jobDir.resolve("hls");
        this.thumbnailDir = jobDir.resolve("thumbnails");
        try {
            Files.createDirectories(hlsDir);
            Files.createDirectories(thumbnailDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create workspace " + jobDir, e);
        }
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public Path hlsDir() {
        return hlsDir;
    }

    public Path thumbnailDir() {
        return thumbnailDir;
    }

    public UUID videoId() {
        return videoId;
    }

    /**
     * Deletes everything this job wrote. Never throws: a cleanup failure must not replace
     * the real error that brought us here. The cost of a leaked directory is reclaimed by
     * the container's ephemeral storage limit, not by crashing the listener.
     */
    @Override
    public void close() {
        if (!Files.exists(jobDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(jobDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {} during workspace cleanup", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk workspace {} during cleanup", jobDir, e);
        }
    }

    /** Reports total bytes held, for the log line that accompanies every job. */
    public long sizeOnDisk() {
        try (Stream<Path> paths = Files.walk(jobDir)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public Path jobDir() {
        return jobDir;
    }
}
