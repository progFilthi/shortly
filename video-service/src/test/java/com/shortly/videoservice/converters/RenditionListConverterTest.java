package com.shortly.videoservice.converters;

import com.shortly.contracts.events.RenditionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ladder is persisted as JSON, which means a schema mismatch between the transcoder's
 * record and this converter's expectations is a runtime failure on read rather than a compile
 * error. These assertions pin the round trip and, more importantly, the behaviour on damaged
 * data.
 */
class RenditionListConverterTest {

    private final RenditionListConverter converter = new RenditionListConverter();

    @Test
    void roundTripsAFullLadder() {
        List<RenditionInfo> ladder = List.of(
                new RenditionInfo("v0", 1080, 1920, 4500, 128),
                new RenditionInfo("v1", 720, 1280, 2200, 128),
                new RenditionInfo("v5", 180, 320, 150, 128));

        String stored = converter.convertToDatabaseColumn(ladder);
        assertThat(stored).contains("v0").contains("1080").contains("1920");

        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(ladder);
    }

    @Test
    void storesNullForAnAbsentLadder() {
        // A video that has not been transcoded has no ladder. Storing "[]" instead of NULL
        // would make "never transcoded" indistinguishable from "transcoded to nothing".
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    void readsNullAndEmptyAsAnEmptyLadder() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void degradesToAnEmptyLadderOnCorruptJsonRatherThanFailing() {
        // A hand-edited or truncated column must not make the whole entity unreadable. Throwing
        // here would take down the feed for every user, when the real cost is only a missing
        // rendition list on one video.
        assertThat(converter.convertToEntityAttribute("{not json")).isEmpty();
        assertThat(converter.convertToEntityAttribute("[{\"name\":\"v0\"}")).isEmpty();
    }

    @Test
    void toleratesAnUnknownFieldFromANewerProducer() {
        // Forward compatibility: a transcoder that adds a field must not break an older
        // video-service, or a rolling deploy deadlocks the two against each other.
        String fromTheFuture = """
                [{"name":"v0","width":1080,"height":1920,"videoKbps":4500,
                  "audioKbps":128,"futureField":"ignored"}]
                """;

        List<RenditionInfo> read = converter.convertToEntityAttribute(fromTheFuture);

        assertThat(read).hasSize(1);
        assertThat(read.get(0).name()).isEqualTo("v0");
        assertThat(read.get(0).width()).isEqualTo(1080);
    }
}
