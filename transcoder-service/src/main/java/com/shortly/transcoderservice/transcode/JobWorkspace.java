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

/** Per-job scratch space on the container's local disk. ffmpeg cannot work efficiently against an
 * pass re-reads the file. */
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

    /** Deletes everything this job wrote. */
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
