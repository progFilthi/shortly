package com.shortly.videoservice.mappers;

import com.shortly.contracts.events.RenditionInfo;
import com.shortly.videoservice.models.Video;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-rendition playlist URLs are derived from the manifest URL rather than stored, so the two
 * cannot drift apart. That makes the derivation itself the thing worth testing - a bad slice
 * index here produces URLs that 404 for every client, with no error anywhere in our logs.
 */
class VideoMapperRenditionUrlTest {

    private static final UUID VIDEO_ID = UUID.randomUUID();
    private static final String MANIFEST =
            "https://cdn.example.com/hls/" + VIDEO_ID + "/master.m3u8";

    @Test
    void derivesEachVariantPlaylistFromTheManifestUrl() {
        Video video = videoWith(MANIFEST, List.of(
                new RenditionInfo("v0", 1080, 1920, 4500, 128),
                new RenditionInfo("v1", 720, 1280, 2200, 128),
                new RenditionInfo("v5", 180, 320, 150, 128)));

        var renditions = VideoMapper.toRenditionResponses(video);

        assertThat(renditions).hasSize(3);
        assertThat(renditions.get(0).playlistUrl())
                .isEqualTo("https://cdn.example.com/hls/" + VIDEO_ID + "/v0/index.m3u8");
        assertThat(renditions.get(1).playlistUrl())
                .isEqualTo("https://cdn.example.com/hls/" + VIDEO_ID + "/v1/index.m3u8");
        assertThat(renditions.get(2).playlistUrl())
                .isEqualTo("https://cdn.example.com/hls/" + VIDEO_ID + "/v5/index.m3u8");
    }

    @Test
    void carriesThroughTheDimensionsAndBitrate() {
        Video video = videoWith(MANIFEST, List.of(new RenditionInfo("v2", 540, 960, 1100, 128)));

        var rendition = VideoMapper.toRenditionResponses(video).get(0);

        assertThat(rendition.name()).isEqualTo("v2");
        assertThat(rendition.width()).isEqualTo(540);
        assertThat(rendition.height()).isEqualTo(960);
        assertThat(rendition.videoKbps()).isEqualTo(1100);
    }

    @Test
    void returnsNothingBeforeTheLadderExists() {
        // A video still in UPLOADING or PROCESSING has no manifest, so there is nothing to
        // derive. Returning placeholder URLs would be worse than returning none.
        Video uploading = new Video();
        uploading.setHlsManifestUrl(null);
        uploading.setVideoUrl(null);
        uploading.setRenditions(List.of(new RenditionInfo("v0", 1080, 1920, 4500, 128)));

        assertThat(VideoMapper.toRenditionResponses(uploading)).isEmpty();
    }

    @Test
    void returnsNothingWhenTheLadderIsEmpty() {
        assertThat(VideoMapper.toRenditionResponses(videoWith(MANIFEST, List.of()))).isEmpty();
        assertThat(VideoMapper.toRenditionResponses(videoWith(MANIFEST, null))).isEmpty();
    }

    @Test
    void fallsBackToTheLegacyVideoUrlWhenNoManifestIsRecorded() {
        // A ladder produced by a build that did not yet record hlsManifestUrl. Falling back
        // keeps those videos playable instead of returning an empty ladder forever.
        Video video = new Video();
        video.setHlsManifestUrl(null);
        video.setVideoUrl(MANIFEST);
        video.setRenditions(List.of(new RenditionInfo("v0", 1080, 1920, 4500, 128)));

        assertThat(VideoMapper.toRenditionResponses(video).get(0).playlistUrl())
                .isEqualTo("https://cdn.example.com/hls/" + VIDEO_ID + "/v0/index.m3u8");
    }

    private Video videoWith(String manifestUrl, List<RenditionInfo> renditions) {
        Video video = new Video();
        video.setHlsManifestUrl(manifestUrl);
        video.setVideoUrl(manifestUrl);
        video.setRenditions(renditions);
        return video;
    }
}
