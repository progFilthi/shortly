package com.shortly.transcoderservice.media;

import net.bramp.ffmpeg.probe.FFmpegStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotation handling is the single easiest thing to get silently wrong in a video pipeline.
 * <p>
 * iPhone portrait video is stored as 1920x1080 with a 90° display matrix. ffmpeg applies that
 * matrix while decoding, so the encoder sees a 1080x1920 picture — but ffprobe still reports
 * 1920x1080, and every width/height-based check (duration limits, resolution limits, ladder
 * rung selection) would then be reasoning about the wrong orientation.
 * <p>
 * These tests cover the logic this service owns: reading the matrix out of the probe result,
 * normalising its sign, and swapping the reported dimensions. They deliberately do NOT claim to
 * cover ffmpeg's own decode-time rotation, which cannot be exercised here because ffmpeg 8
 * cannot write a display matrix — see docs/transcoding.md §13.
 */
class MediaProbeRotationTest {

    @Test
    void treatsAnAbsentSideDataListAsNoRotation() {
        assertThat(readRotation(streamWithSideData(null))).isZero();
    }

    @Test
    void treatsAnEmptySideDataListAsNoRotation() {
        assertThat(readRotation(streamWithSideData(new FFmpegStream.SideData[0]))).isZero();
    }

    @Test
    void matchesTheExactSideDataTypeFfprobeEmits() {
        // The literal string ffprobe writes is "Display Matrix", with a space. A naive
        // contains("displaymatrix") check on the lowercased value never matches, which turns
        // rotation detection off silently - so this asserts the real value, not a convenient one.
        FFmpegStream.SideData matrix = new FFmpegStream.SideData();
        matrix.side_data_type = "Display Matrix";
        matrix.rotation = -90;

        assertThat(readRotation(streamWithSideData(new FFmpegStream.SideData[]{matrix})))
                .isEqualTo(90);
    }

    @Test
    void isInsensitiveToHowTheSideDataTypeIsPunctuated() {
        for (String spelling : new String[]{
                "Display Matrix", "display matrix", "DISPLAY_MATRIX", "display-matrix"}) {
            FFmpegStream.SideData matrix = new FFmpegStream.SideData();
            matrix.side_data_type = spelling;
            matrix.rotation = -90;

            assertThat(readRotation(streamWithSideData(new FFmpegStream.SideData[]{matrix})))
                    .as("side_data_type %s should be recognised", spelling)
                    .isEqualTo(90);
        }
    }

    @Test
    void ignoresSideDataThatIsNotADisplayMatrix() {
        FFmpegStream.SideData other = new FFmpegStream.SideData();
        other.side_data_type = "Stereo 3D side data";
        other.rotation = 90;

        assertThat(readRotation(streamWithSideData(new FFmpegStream.SideData[]{other}))).isZero();
    }

    @Test
    void reportsRotationAsClockwiseDegrees() {
        // ffprobe reports the display matrix COUNTER-clockwise, and the common iPhone portrait
        // case is a negative angle (-90). Everything downstream wants clockwise, so the sign is
        // flipped: raw -90 becomes 90, which is also what makes the width/height swap come out
        // right, since -90 CCW is a 90 CW turn.
        assertThat(readRotation(streamWithSideData(matrixWith(-90)))).isEqualTo(90);
        assertThat(readRotation(streamWithSideData(matrixWith(90)))).isEqualTo(270);
        assertThat(readRotation(streamWithSideData(matrixWith(180)))).isEqualTo(180);
        assertThat(readRotation(streamWithSideData(matrixWith(-180)))).isEqualTo(180);
    }

    @Test
    void aQuarterTurnInEitherDirectionSwapsTheDimensions() {
        // The invariant the pipeline actually depends on. Both 90 and 270 are quarter turns, so
        // both must swap; getting the sign convention wrong must not change that, or a portrait
        // iPhone clip gets validated and laddered as if it were landscape.
        for (int raw : new int[]{-90, 90, -270, 270}) {
            int clockwise = readRotation(streamWithSideData(matrixWith(raw)));
            assertThat(clockwise == 90 || clockwise == 270)
                    .as("raw rotation %d normalises to %d, which must be a quarter turn", raw, clockwise)
                    .isTrue();
        }
    }

    @Test
    void keepsZeroAtZeroRatherThanMappingItTo360() {
        // A 0 rotation reported as 360 would not break the quarter-turn check, but it would log
        // a full turn for a clip that has none and would defeat any future "is rotated" check.
        assertThat(readRotation(streamWithSideData(matrixWith(0)))).isZero();
    }

    @Test
    void aQuarterTurnSwapsTheReportedDisplayDimensions() {
        // 1080x1920 reported by ffprobe, actually shown rotated as 1920x1080.
        MediaMetadata rotated = metadata(1080, 1920, 90);
        assertThat(rotated.displayWidth()).isEqualTo(1920);
        assertThat(rotated.displayHeight()).isEqualTo(1080);
        // The unrotated values are retained, because they are what ffmpeg will actually decode.
        assertThat(rotated.width()).isEqualTo(1080);
        assertThat(rotated.height()).isEqualTo(1920);
        assertThat(rotated.isPortrait()).isFalse();
    }

    @Test
    void aHalfTurnKeepsTheDimensions() {
        MediaMetadata rotated = metadata(1080, 1920, 180);
        assertThat(rotated.displayWidth()).isEqualTo(1080);
        assertThat(rotated.displayHeight()).isEqualTo(1920);
    }

    @Test
    void noRotationLeavesTheDimensionsAlone() {
        MediaMetadata plain = metadata(1080, 1920, 0);
        assertThat(plain.displayWidth()).isEqualTo(1080);
        assertThat(plain.displayHeight()).isEqualTo(1920);
        assertThat(plain.isPortrait()).isTrue();
    }

    @Test
    void detectsTheCanonicalNineBySixteenAspect() {
        assertThat(metadata(1080, 1920, 0).isCanonicalAspect()).isTrue();
        assertThat(metadata(1080, 1350, 0).isCanonicalAspect()).isFalse();
        // 4:3, the other common phone-ish aspect.
        assertThat(metadata(1440, 1080, 0).isCanonicalAspect()).isFalse();
    }

    @Test
    void identifiesHdrByTransferCharacteristic() {
        assertThat(withColorTransfer("smpte2084").isHdr()).isTrue();   // PQ
        assertThat(withColorTransfer("arib-std-b67").isHdr()).isTrue(); // HLG
        assertThat(withColorTransfer("bt709").isHdr()).isFalse();
        assertThat(withColorTransfer(null).isHdr()).isFalse();
    }

    @Test
    void identifiesHighBitDepthPixelFormats() {
        assertThat(withPixelFormat("yuv420p10le").isHighBitDepth()).isTrue();
        assertThat(withPixelFormat("yuv444p12le").isHighBitDepth()).isTrue();
        assertThat(withPixelFormat("yuv420p").isHighBitDepth()).isFalse();
    }

    @Test
    void treatsAnUnreportableDurationAsUnreliable() {
        // Duration zero must not be read as "an instant video"; it means the container did not
        // say, and the validator turns that into a terminal SOURCE_CORRUPT.
        assertThat(withDuration(Duration.ZERO).durationReliable()).isFalse();
        assertThat(withDuration(Duration.ofSeconds(1)).durationReliable()).isTrue();
        assertThat(withDuration(Duration.ofSeconds(-1)).durationReliable()).isFalse();
    }

    /* ------------------------------- test helpers ------------------------------ */

    private static int readRotation(FFmpegStream stream) {
        return MediaProbeService.readRotation(stream);
    }

    private static FFmpegStream streamWithSideData(FFmpegStream.SideData[] sideData) {
        FFmpegStream stream = new FFmpegStream();
        stream.width = 1920;
        stream.height = 1080;
        stream.side_data_list = sideData;
        return stream;
    }

    private static FFmpegStream.SideData[] matrixWith(int rotation) {
        FFmpegStream.SideData matrix = new FFmpegStream.SideData();
        matrix.side_data_type = "Display Matrix";
        matrix.rotation = rotation;
        return new FFmpegStream.SideData[]{matrix};
    }

    private static MediaMetadata metadata(int width, int height, int rotation) {
        boolean quarterTurn = rotation == 90 || rotation == 270;
        return new MediaMetadata(
                Duration.ofSeconds(30), width, height,
                quarterTurn ? height : width, quarterTurn ? width : height,
                rotation, 30d, "h264", "yuv420p", "bt709",
                true, "aac", 2, 48000, 0, 1);
    }

    private static MediaMetadata withColorTransfer(String transfer) {
        MediaMetadata base = metadata(1080, 1920, 0);
        return copy(base, base.pixelFormat(), transfer);
    }

    private static MediaMetadata withPixelFormat(String pixelFormat) {
        MediaMetadata base = metadata(1080, 1920, 0);
        return copy(base, pixelFormat, base.colorTransfer());
    }

    private static MediaMetadata withDuration(Duration duration) {
        MediaMetadata base = metadata(1080, 1920, 0);
        return new MediaMetadata(
                duration, base.width(), base.height(), base.displayWidth(), base.displayHeight(),
                base.rotationDegrees(), base.frameRate(), base.videoCodec(), base.pixelFormat(),
                base.colorTransfer(), base.hasAudio(), base.audioCodec(), base.audioChannels(),
                base.audioSampleRate(), base.videoStreamIndex(), base.audioStreamIndex());
    }

    private static MediaMetadata copy(MediaMetadata base, String pixelFormat, String transfer) {
        return new MediaMetadata(
                base.duration(), base.width(), base.height(),
                base.displayWidth(), base.displayHeight(), base.rotationDegrees(),
                base.frameRate(), base.videoCodec(), pixelFormat, transfer,
                base.hasAudio(), base.audioCodec(), base.audioChannels(),
                base.audioSampleRate(), base.videoStreamIndex(), base.audioStreamIndex());
    }
}
