package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.WorldLightTable;
import dev.theredja.src2mc.bundle.WorldLightTable.Light;
import java.util.List;

/**
 * A uniform grid over a map's selectable world lights, so a prop looks at the lights whose radius
 * reaches its cell instead of every light in the map. Built once per map, immutable, any thread.
 * Lights without a usable radius (skylights, quake lights) and ones that would fill too many cells
 * are listed apart and always considered. A point outside the grid is out of every ranged light's
 * reach.
 */
final class LightGrid {
    private static final double CELL = 8.0;
    private static final int MAX_CELLS = 1 << 22;
    private static final int MAX_CELLS_PER_LIGHT = 4096;

    final List<Light> lights;
    /** Indices of the lights always considered. */
    final int[] always;
    private final double minX, minY, minZ, cell;
    private final int nx, ny, nz;
    /** Per cell, its lights' indices are {@code items[start[c] .. start[c + 1])}. */
    private final int[] start, items;

    LightGrid(List<Light> lights) {
        this.lights = lights;
        int n = lights.size();
        boolean[] ranged = new boolean[n];
        double lo0 = Double.POSITIVE_INFINITY, lo1 = lo0, lo2 = lo0, hi0 = Double.NEGATIVE_INFINITY, hi1 = hi0, hi2 = hi0;
        int alwaysCount = 0;
        for (int i = 0; i < n; i++) {
            Light light = lights.get(i);
            if (light.type() == WorldLightTable.TYPE_SKYLIGHT || light.type() == WorldLightTable.TYPE_QUAKE) { alwaysCount++; continue; }
            ranged[i] = true;
            float r = light.radius();
            lo0 = Math.min(lo0, light.origin()[0] - r); hi0 = Math.max(hi0, light.origin()[0] + r);
            lo1 = Math.min(lo1, light.origin()[1] - r); hi1 = Math.max(hi1, light.origin()[1] + r);
            lo2 = Math.min(lo2, light.origin()[2] - r); hi2 = Math.max(hi2, light.origin()[2] + r);
        }
        double size = CELL;
        int gx = 1, gy = 1, gz = 1;
        if (lo0 <= hi0) {
            while (true) {
                gx = (int) Math.ceil((hi0 - lo0) / size) + 1; gy = (int) Math.ceil((hi1 - lo1) / size) + 1; gz = (int) Math.ceil((hi2 - lo2) / size) + 1;
                if ((long) gx * gy * gz <= MAX_CELLS) break;
                size *= 2;
            }
        } else {
            lo0 = lo1 = lo2 = 0;
        }
        minX = lo0; minY = lo1; minZ = lo2; cell = size; nx = gx; ny = gy; nz = gz;
        int[] counts = new int[nx * ny * nz + 1];
        int[] spans = new int[n * 6];
        for (int i = 0; i < n; i++) {
            if (!ranged[i]) continue;
            Light light = lights.get(i);
            float r = light.radius();
            spans[i * 6] = index(light.origin()[0] - r - minX, nx); spans[i * 6 + 3] = index(light.origin()[0] + r - minX, nx);
            spans[i * 6 + 1] = index(light.origin()[1] - r - minY, ny); spans[i * 6 + 4] = index(light.origin()[1] + r - minY, ny);
            spans[i * 6 + 2] = index(light.origin()[2] - r - minZ, nz); spans[i * 6 + 5] = index(light.origin()[2] + r - minZ, nz);
            long cells = (long) (spans[i * 6 + 3] - spans[i * 6] + 1) * (spans[i * 6 + 4] - spans[i * 6 + 1] + 1) * (spans[i * 6 + 5] - spans[i * 6 + 2] + 1);
            if (cells > MAX_CELLS_PER_LIGHT) { ranged[i] = false; alwaysCount++; }
        }
        always = new int[alwaysCount];
        int a = 0;
        for (int i = 0; i < n; i++) {
            if (ranged[i]) {
                for (int z = spans[i * 6 + 2]; z <= spans[i * 6 + 5]; z++)
                    for (int y = spans[i * 6 + 1]; y <= spans[i * 6 + 4]; y++)
                        for (int x = spans[i * 6]; x <= spans[i * 6 + 3]; x++) counts[(z * ny + y) * nx + x + 1]++;
            } else {
                always[a++] = i;
            }
        }
        for (int c = 0; c < nx * ny * nz; c++) counts[c + 1] += counts[c];
        start = counts;
        items = new int[start[nx * ny * nz]];
        int[] fill = new int[nx * ny * nz];
        for (int i = 0; i < n; i++) {
            if (!ranged[i]) continue;
            for (int z = spans[i * 6 + 2]; z <= spans[i * 6 + 5]; z++)
                for (int y = spans[i * 6 + 1]; y <= spans[i * 6 + 4]; y++)
                    for (int x = spans[i * 6]; x <= spans[i * 6 + 3]; x++) {
                        int c = (z * ny + y) * nx + x;
                        items[start[c] + fill[c]++] = i;
                    }
        }
    }

    private int index(double offset, int dim) {
        return Math.max(0, Math.min(dim - 1, (int) Math.floor(offset / cell)));
    }

    /** The cell of a point, or -1 outside the grid. */
    private int cellOf(double x, double y, double z) {
        double ox = x - minX, oy = y - minY, oz = z - minZ;
        if (ox < 0 || oy < 0 || oz < 0) return -1;
        int cx = (int) (ox / cell), cy = (int) (oy / cell), cz = (int) (oz / cell);
        if (cx >= nx || cy >= ny || cz >= nz) return -1;
        return (cz * ny + cy) * nx + cx;
    }

    /** First index into {@link #items()} of the lights whose radius reaches the point's cell. */
    int cellStart(double x, double y, double z) {
        int c = cellOf(x, y, z);
        return c < 0 ? 0 : start[c];
    }

    /** One past the last index; equals {@link #cellStart} when the point's cell holds none. */
    int cellEnd(double x, double y, double z) {
        int c = cellOf(x, y, z);
        return c < 0 ? 0 : start[c + 1];
    }

    int[] items() { return items; }

    int size() { return lights.size(); }
}
