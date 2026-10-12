package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.LightTable.SampleWeights;
import dev.theredja.src2mc.bundle.SunVisibilityTable;

/**
 * How much of a world light reaches a prop through the map's walls (the lists of the sun
 * visibility file, version 2): 1 or 0 at a static prop's lighting origin by its own list; for a
 * moving prop the weight of the ambient samples whose list holds the light, the same blend the
 * cube and the sun visibility use. Not thread-safe: one per selection.
 */
final class LightVisibility {
    private SunVisibilityTable table;
    private int list = -1;
    private SampleWeights samples;
    /** How many lights {@link #weight} found fully hidden since {@link #culled} was last reset. */
    int culled;

    /** The visibility at a static prop's lighting origin; null when the table has no list for it. */
    static LightVisibility ofList(SunVisibilityTable table, int list) {
        if (table == null || !table.hasLists() || list < 0) return null;
        LightVisibility vis = new LightVisibility();
        vis.table = table;
        vis.list = list;
        return vis;
    }

    /** A reusable visibility over ambient samples; {@link #setSamples} before each selection. */
    static LightVisibility ofSamples() { return new LightVisibility(); }

    /** Points this at {@code table} and the samples a cube lookup used; false when it cannot filter. */
    boolean setSamples(SunVisibilityTable table, SampleWeights samples) {
        this.table = table;
        this.list = -1;
        this.samples = samples;
        return table != null && table.hasLists() && samples.count() > 0;
    }

    /** 0 to 1: how much of world light {@code light} (index in the world light table) reaches the point. */
    float weight(int light) {
        float w;
        if (samples == null) {
            w = table.listContains(list, light) ? 1f : 0f;
        } else {
            w = 0f;
            for (int i = 0, n = samples.count(); i < n; i++) {
                if (table.listContains(samples.index(i), light)) w += samples.weight(i);
            }
            if (w > 1f) w = 1f;
        }
        if (w <= 0f) culled++;
        return w;
    }
}
