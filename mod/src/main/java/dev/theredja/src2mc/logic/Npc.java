package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;

/**
 * An actor ({@code npc_*}, {@code generic_actor}, {@code cycler_actor}, {@code monster_generic}): drawn as an animated logic
 * prop in the model its class chooses (the converter's {@code bsp/npcs.rs}). Tier 1, milestone M2: it stands where the map
 * puts it and idles, {@code SelectWeightedSequence(ACT_IDLE)}, picking again whenever an idle that does not loop has played
 * out, as an NPC in {@code SCHED_IDLE_STAND} keeps its activity. It takes none of {@code CDynamicProp}'s inputs; the ones all
 * entities take ({@code Kill}, {@code SetParent}, ...) still work.
 */
final class Npc extends DynamicProp {
    static final String IDLE = "ACT_IDLE";
    /** {@code SF_NPC_TEMPLATE} (ai_basenpc.h:151): kept for an {@code npc_template_maker}, never spawned itself. */
    static final int TEMPLATE = 1 << 11;
    /** {@code SF_COMBINE_CAMERA_STARTINACTIVE} (npc_combinecamera.cpp:57). */
    private static final int CAMERA_START_INACTIVE = 0x80;

    Npc(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

    @Override void spawn() {
        // MapEntity_ParseAllEntities does not spawn a template (mapentities.cpp:365).
        if (hasSpawnFlags(TEMPLATE)) {
            remove();
            return;
        }
        if (animation == null) return;
        // The prop's own think re-plays its default when a sequence ends: an idle that stops is followed by another.
        defaultAnim = idleActivity();
        setAnimation(defaultAnim);
    }

    /**
     * {@code ACT_IDLE}; a combine camera that starts active deploys to its open idle, through its transition, and an
     * inactive one stays closed ({@code CNPC_CombineCamera::Spawn}, {@code Deploy}, npc_combinecamera.cpp:343-364, 447).
     * The converter keeps the same activity's sequences ({@code bsp/npcs.rs idle_activity}).
     */
    private String idleActivity() {
        if (!"npc_combine_camera".equals(classname)) return IDLE;
        return hasSpawnFlags(CAMERA_START_INACTIVE) ? "ACT_COMBINE_CAMERA_CLOSED_IDLE" : "ACT_COMBINE_CAMERA_OPEN_IDLE";
    }

    @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) { return false; }
}
