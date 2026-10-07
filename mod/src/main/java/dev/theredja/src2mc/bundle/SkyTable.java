package dev.theredja.src2mc.bundle;

import java.util.List;

/**
 * A map's sky (format.md section 20): the faces Source draws its skybox through, and the six
 * sides of the 2D skybox drawn in them.
 *
 * @param sides content IDs of {@code sky/<id>.png}, in {@link #SUFFIXES} order; null for a side
 *     the converter could not read, drawn black
 * @param faces each face's corners as {@code x, y, z} triples, map-local blocks, counter-clockwise
 *     seen from the side the sky is drawn on
 */
public record SkyTable(List<String> sides, List<float[]> faces) {
    /** The sides in stored order, by their Source material suffix. */
    public static final List<String> SUFFIXES = List.of("rt", "lf", "bk", "ft", "up", "dn");
    public static final int MAX_FACE_POINTS = 256;

    public SkyTable {
        sides = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sides));
        faces = List.copyOf(faces);
    }

    /** Where side {@code side}'s image is in the bundle; null for a missing side. */
    public String sidePath(int side) {
        String id = sides.get(side);
        return id == null ? null : "sky/" + id + ".png";
    }

    /** Corners over every face. */
    public int points() {
        int points = 0;
        for (float[] face : faces) points += face.length / 3;
        return points;
    }
}
