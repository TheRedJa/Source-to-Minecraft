package dev.theredja.src2mc.bundle;

import java.util.List;

/**
 * The cubemaps a map's materials reflect (format.md section 25): per cubemap its face side and
 * six faces in Direct3D order (+X, -X, +Y, -Y, +Z, -Z in Source axes), each row by row from the
 * top, linear RGB as little-endian half floats.
 */
public record CubemapTable(List<Cube> cubes) {
    public CubemapTable { cubes = List.copyOf(cubes); }

    public static final int MAX_SIZE = 512;
    public static final int MAX_CUBES = 4096;

    /** {@code faces}: {@code 6 * size * size} texels of three half floats. */
    public record Cube(int size, byte[] faces) {}
}
