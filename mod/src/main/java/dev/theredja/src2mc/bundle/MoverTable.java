package dev.theredja.src2mc.bundle;

import java.util.List;

/**
 * The map's moving entities (format.md section 17): doors, buttons, lifts, trains and
 * {@code func_brush}, each a small map of its own that the world leaves out and Sable carries as
 * a sub-level. Everything is in mover-local cells; cell (0, 0, 0) sits at the map-local
 * {@link Mover#cellOrigin()} when the entity is where the map compiled it.
 *
 * Equality is identity, like the other large tables: a generation never rebuilds one.
 */
public final class MoverTable {
    private final List<Mover> movers;
    private final int[] byEntity;

    public MoverTable(List<Mover> movers) {
        this.movers = List.copyOf(movers);
        int max = -1;
        for (Mover mover : this.movers) max = Math.max(max, mover.entity());
        byEntity = new int[max + 1];
        java.util.Arrays.fill(byEntity, -1);
        for (int i = 0; i < this.movers.size(); i++) byEntity[this.movers.get(i).entity()] = i;
    }

    public List<Mover> movers() { return movers; }

    /** The mover index of a logic entity, or -1 when the entity is not a mover. */
    public int indexOfEntity(int entity) {
        return entity >= 0 && entity < byEntity.length ? byEntity[entity] : -1;
    }

    /** A prop carried by a mover, placed as its props.s2props record would have been, in mover-local blocks. */
    public record Prop(int entity, int model, double[] translation, double[] rotation, double scale, int skin) {
        public Prop {
            translation = translation.clone();
            rotation = rotation.clone();
        }
        @Override public double[] translation() { return translation.clone(); }
        @Override public double[] rotation() { return rotation.clone(); }
    }

    /**
     * One moving entity. {@code surfaceBlocks} and {@code carrierBlocks} are mover-local cells
     * packed three ints each; {@code surfaces} and {@code collision} are null when empty.
     */
    public record Mover(int entity, String classname, int[] cellOrigin, int[] size, SurfaceTable surfaces,
                        CollisionTable collision, int[] surfaceBlocks, int[] carrierBlocks, List<Prop> props) {
        public Mover {
            cellOrigin = cellOrigin.clone();
            size = size.clone();
            surfaceBlocks = surfaceBlocks.clone();
            carrierBlocks = carrierBlocks.clone();
            props = List.copyOf(props);
        }
        @Override public int[] cellOrigin() { return cellOrigin.clone(); }
        @Override public int[] size() { return size.clone(); }
        @Override public int[] surfaceBlocks() { return surfaceBlocks.clone(); }
        @Override public int[] carrierBlocks() { return carrierBlocks.clone(); }

        public int originX() { return cellOrigin[0]; }
        public int originY() { return cellOrigin[1]; }
        public int originZ() { return cellOrigin[2]; }
        public int sizeX() { return size[0]; }
        public int sizeY() { return size[1]; }
        public int sizeZ() { return size[2]; }

        /** Every cell that holds one of the mover's blocks, surface cells first, packed three ints each. */
        public int[] blockCells() {
            int[] cells = new int[surfaceBlocks.length + carrierBlocks.length];
            System.arraycopy(surfaceBlocks, 0, cells, 0, surfaceBlocks.length);
            System.arraycopy(carrierBlocks, 0, cells, surfaceBlocks.length, carrierBlocks.length);
            return cells;
        }

        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
