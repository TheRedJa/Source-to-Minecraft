package dev.theredja.src2mc.bundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The props the map's logic changes (format.md section 18): each one's placement, the model
 * reference of every skin family it can switch to, whether it spawns hidden, and its own
 * collision when the logic can take that away. Every other prop is drawn merged and collides
 * merged; these are drawn one by one and their collision is added while they stand.
 *
 * Equality is identity, like the other large tables: a generation never rebuilds one.
 */
public final class LogicPropTable {
    private final List<Prop> props;
    private final Map<String, Prop> byStableId = new HashMap<>();
    private final Map<Integer, Prop> byEntity = new HashMap<>();

    public LogicPropTable(List<Prop> props) {
        this.props = List.copyOf(props);
        for (Prop prop : this.props) {
            byEntity.put(prop.entity(), prop);
            if (prop.stableId() != null) byStableId.put(prop.stableId(), prop);
        }
    }

    public List<Prop> props() { return props; }

    /** The logic prop placed as this {@code props.s2props} record, or null. */
    public Prop byStableId(String stableId) { return byStableId.get(stableId); }

    /** The logic prop of a lump entity, or null. */
    public Prop byEntity(int entity) { return byEntity.get(entity); }

    /**
     * One prop the logic changes. {@code stableId} names its placed record, or is null for one
     * riding mover {@code mover} (-1 otherwise). {@code collision} is map-local and null when it
     * has no collision the logic can remove.
     */
    public record Prop(int entity, String stableId, int mover, int[] skins, int skin, boolean startHidden,
                       CollisionTable collision) {
        public Prop {
            skins = skins.clone();
        }
        @Override public int[] skins() { return skins.clone(); }

        /** The model reference wearing {@code skin}; a skin the model lacks is drawn as the first, as Source draws it. */
        public int model(int skin) { return skin >= 0 && skin < skins.length ? skins[skin] : skins[0]; }

        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
