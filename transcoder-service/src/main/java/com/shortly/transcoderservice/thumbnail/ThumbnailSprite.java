package com.shortly.transcoderservice.thumbnail;

/** A sprite sheet of candidate cover frames, plus the metadata a client needs to map a scrub
 * position to a tile. */
public record ThumbnailSprite(
        String url,
        int frameCount,
        int columns,
        int tileWidth,
        int tileHeight,
        double[] intervals
) {

    /** The timestamp a given tile index represents, clamped to the available range. */
    public double intervalFor(int tileIndex) {
        if (intervals == null || intervals.length == 0) {
            return 0d;
        }
        if (tileIndex < 0) {
            return intervals[0];
        }
        if (tileIndex >= intervals.length) {
            return intervals[intervals.length - 1];
        }
        return intervals[tileIndex];
    }

    /** The tile index a normalised position corresponds to, for a client that scrubs a slider. */
    public int tileForFraction(double fraction) {
        if (intervals == null || intervals.length == 0) {
            return 0;
        }
        double clamped = Math.max(0d, Math.min(1d, fraction));
        return (int) Math.round(clamped * (intervals.length - 1));
    }
}
