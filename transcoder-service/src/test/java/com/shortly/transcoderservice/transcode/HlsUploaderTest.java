package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.storage.StorageException;
import com.shortly.transcoderservice.storage.TranscoderObjectStore;
import com.shortly.transcoderservice.storage.TranscoderStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.shortly.transcoderservice.transcode.TranscodeFixtures.productionDefaults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The variant directory names the uploader must address.
 *
 * <p>{@code HlsCommandBuilder} emits {@code v%v}, so ffmpeg numbers variants by their position in the
 * ladder it was actually asked to produce, and {@code master.m3u8} references them by those relative
 * paths. When {@code MediaValidator#applicableRungs} drops rungs that would upscale, the applicable
 * ladder is shorter than the configured one and those positions stop matching the configured names.
 * A 720x1280 source keeps rungs {@code v3 v4 v5} but ffmpeg writes {@code v0 v1 v2}. */
class HlsUploaderTest {

    private static final UUID VIDEO_ID = UUID.fromString("1ae8f39f-db9e-49b0-88bf-3515f6428155");

    private final List<String> uploadedKeys = new ArrayList<>();

    @Test
    void addressesVariantsByPositionWhenTheApplicableLadderIsShorterThanConfigured(@TempDir Path dir)
            throws IOException {
        // The bug: applicable rungs are v3/v4/v5, so ffmpeg's variant indices are 0/1/2.
        writeLadder(dir, "v0", "v1", "v2");

        List<String> keys = uploader().upload(VIDEO_ID, dir, List.of("v3", "v4", "v5"));

        assertThat(keys).contains(
                "hls/" + VIDEO_ID + "/master.m3u8",
                "hls/" + VIDEO_ID + "/v0/index.m3u8",
                "hls/" + VIDEO_ID + "/v1/index.m3u8",
                "hls/" + VIDEO_ID + "/v2/index.m3u8",
                "hls/" + VIDEO_ID + "/v0/seg_00000.ts",
                "hls/" + VIDEO_ID + "/v2/seg_00000.ts");
        assertThat(keys).noneMatch(key -> key.contains("/v3/"));
    }

    /** The full six-rung ladder, where positions and names coincide, must keep working. */
    @Test
    void addressesVariantsByNameWhenEveryConfiguredRungApplies(@TempDir Path dir) throws IOException {
        writeLadder(dir, "v0", "v1", "v2", "v3", "v4", "v5");

        List<String> keys = uploader().upload(VIDEO_ID, dir,
                List.of("v0", "v1", "v2", "v3", "v4", "v5"));

        assertThat(keys).contains(
                "hls/" + VIDEO_ID + "/v0/index.m3u8",
                "hls/" + VIDEO_ID + "/v5/index.m3u8");
    }

    /** A layout keyed by configured rung name is not what ffmpeg produces, so it must not be
     * silently accepted: that shape is exactly what the old code looked for. */
    @Test
    void rejectsALayoutKeyedByConfiguredRungName(@TempDir Path dir) throws IOException {
        writeLadder(dir, "v3", "v4", "v5");

        assertThatThrownBy(() -> uploader().upload(VIDEO_ID, dir, List.of("v3", "v4", "v5")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("v0")
                .hasMessageContaining("rung v3 is variant 0 of 3");
    }

    @Test
    void failsWhenFfmpegProducedNoMasterManifest(@TempDir Path dir) throws IOException {
        writeLadder(dir, "v0");
        Files.delete(dir.resolve("master.m3u8"));

        assertThatThrownBy(() -> uploader().upload(VIDEO_ID, dir, List.of("v3")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("master.m3u8");
    }

    /** ffmpeg's own scratch files, which must never be uploaded. */
    @Test
    void skipsFfmpegTemporaryFiles(@TempDir Path dir) throws IOException {
        writeLadder(dir, "v0");
        Files.writeString(dir.resolve("v0/seg_00000.ts.tmp"), "partial");
        Files.writeString(dir.resolve("v0/playlist.tmp"), "partial");

        List<String> keys = uploader().upload(VIDEO_ID, dir, List.of("v3"));

        assertThat(keys).contains("hls/" + VIDEO_ID + "/v0/seg_00000.ts");
        assertThat(keys).noneMatch(key -> key.endsWith(".tmp"));
    }

    /** One variant directory holding a media playlist and a single segment. */
    private void writeLadder(Path hlsDir, String... variants) throws IOException {
        Files.createDirectories(hlsDir);
        Files.writeString(hlsDir.resolve("master.m3u8"), "#EXTM3U\n");
        for (String variant : variants) {
            Path dir = hlsDir.resolve(variant);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.m3u8"), "#EXTM3U\n");
            Files.write(dir.resolve("seg_00000.ts"), new byte[]{0x47, 0x40, 0x11, 0x10});
        }
    }

    private HlsUploader uploader() {
        TranscoderObjectStore store = mock(TranscoderObjectStore.class);
        when(store.putAsync(anyString(), any(), anyString(), anyString())).thenAnswer(call -> {
            uploadedKeys.add(call.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });

        return new HlsUploader(
                store,
                new TranscoderStorageProperties("shortly-videos-bucket", "https://cdn.example.com",
                        "hls", "video/mp2t", "application/vnd.apple.mpegurl", "image/jpeg",
                        "public, max-age=31536000, immutable", "no-cache"),
                productionDefaults());
    }
}
