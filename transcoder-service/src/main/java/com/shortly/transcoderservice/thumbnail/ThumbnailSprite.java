package com.shortly.transcoderservice.thumbnail;

/**
 * A sprite sheet of candidate cover frames, plus the metadata a client needs to map a
 * scrub position to a tile.
 * <p>
 * This is the "hover to pick your cover" pattern. Rather than making the user choose a
 * timestamp and then wait for a server round trip, the client receives every candidate
 * frame up front as one image, maps the pointer to a tile locally, and sends back only the
 * index it picked.
 *
 * @param url        absolute URL of the sprite JPEG
 * @param frameCount number of tiles in the sheet
 * @param columns    grid width, needed to turn a linear tile index into (row, column)
 * @param tileWidth  rendered width of one tile in the sheet, in pixels
 * @param tileHeight rendered height of one tile in the sheet, in pixels
 * @param intervals  one entry per tile: the timestamp in seconds that tile represents
 */
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

    /**
     * The tile index a normalised position corresponds to, for a client that scrubs a
     * slider. This is the inverse of the sampling grid the sheet was built from.
     */
    public int tileForFraction(double fraction) {
        if (intervals == null || intervals.length == 0) {
            return 0;
        }
        double clamped = Math.max(0d, Math.min(1d, fraction));
        return (int) Math.round(clamped * (intervals.length - 1));
    }
}
