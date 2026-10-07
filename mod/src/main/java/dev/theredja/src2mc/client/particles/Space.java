package dev.theredja.src2mc.client.particles;

/**
 * Where a map's Source coordinates are in the world: Source's origin at {@code (ox, oy, oz)}, 32
 * units a block, Source's X east, Y north (Minecraft's -Z) and Z up.
 */
record Space(double ox, double oy, double oz) {
    static final double UNITS = 32.0;

    double worldX(double x) { return ox + x / UNITS; }
    double worldY(double z) { return oy + z / UNITS; }
    double worldZ(double y) { return oz - y / UNITS; }

    /** A world point as Source coordinates, into {@code out}. */
    void toSource(double wx, double wy, double wz, double[] out) {
        out[0] = (wx - ox) * UNITS;
        out[1] = -(wz - oz) * UNITS;
        out[2] = (wy - oy) * UNITS;
    }
}
