package dev.theredja.src2mc.menu;

/**
 * Source coordinates in a map's block frame (format section 16): a map-local point {@code (x, y, z)} is
 * Source's {@code ((x - ox) * 32, -(z - oz) * 32, (y - oy) * 32)}.
 */
public record SourceSpace(double ox, double oy, double oz) {
    public static final double UNITS_PER_BLOCK = 32;

    /** The map-local block position of a Source point. */
    public double[] toLocal(double[] source) {
        return new double[]{source[0] / UNITS_PER_BLOCK + ox, source[2] / UNITS_PER_BLOCK + oy, -source[1] / UNITS_PER_BLOCK + oz};
    }

    /** A Source direction as a map-local one (unscaled). */
    public static double[] direction(double[] source) {
        return new double[]{source[0], source[2], -source[1]};
    }
}
