package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.AnimationAsset;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.bundle.LogicTable;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;

/**
 * {@code prop_dynamic}, after {@code CDynamicProp} and the {@code CBaseAnimating} it is built on:
 * shown, hidden and made solid by its inputs, and, for a model with animation (format.md section
 * 19), playing sequences the way the 2013 SDK's {@code props.cpp} and {@code baseanimating.cpp}
 * play them. {@code SetAnimation} goes to a sequence by name or activity, through the model's
 * transition graph when it has one; a think every 0.1 s advances the cycle, and once a sequence
 * that does not loop has played, {@code OnAnimationDone} fires and the {@code DefaultAnim} starts
 * again. A random animator picks an idle sequence now and then instead.
 *
 * <p>The clients see a sequence's start, not every think: the state names the sequence, the cycle
 * at a map time and the rate, and each client advances it on its own, as Source's client
 * interpolates the cycle between updates.
 */
final class DynamicProp extends LogicEntity {
    private static final double THINK_INTERVAL = 0.1, MAX_ANIMTIME_INTERVAL = 0.2;

    /** The model's animation, or null for a model without one. */
    private final AnimationAsset animation;
    private int sequence, goalSequence = -1, transitionDirection, parity, pose = -1;
    private float cycle, playbackRate;
    private double animTime, prevAnimTime;
    private boolean sequenceLoops, sequenceFinished;
    private String defaultAnim;
    private boolean randomAnimator;
    private double nextRandAnim, minRandAnimTime, maxRandAnimTime;
    /** What the clients were last told: the cycle at a map time, and the rate it advances at from there. */
    private float anchorCycle, anchorRate;
    private double anchorTime;
    private boolean animationChanged;

    DynamicProp(MapLogic map, int index, LogicTable.Entity entity) {
        super(map, index, entity);
        LogicPropTable.Prop prop = map.map.logicProps() == null ? null : map.map.logicProps().byEntity(source);
        animation = prop == null || prop.sequence() < 0 ? null : map.map.models().get(prop.model(0)).animation();
    }

    @Override void spawn() {
        noDraw = Variant.integer(key("startdisabled")) != 0;
        String value = key("defaultanim");
        defaultAnim = value == null || value.isEmpty() ? null : value;
        randomAnimator = Variant.integer(key("randomanimation")) != 0;
        minRandAnimTime = number("minanimtime", 0);
        maxRandAnimTime = number("maxanimtime", 0);
        if (animation == null || (!randomAnimator && defaultAnim == null)) return;
        if (randomAnimator) {
            nextRandAnim = map.time() + random(minRandAnimTime, maxRandAnimTime);
            // Source adds the time once more: m_flNextRandAnim already counts from now.
            nextThink = map.time() + nextRandAnim + THINK_INTERVAL;
        } else {
            setAnimation(defaultAnim);
        }
    }

    @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
        switch (input) {
            case "enable", "turnon" -> noDraw = false;
            case "disable", "turnoff" -> noDraw = true;
            case "enablecollision" -> notSolid = false;
            case "disablecollision" -> notSolid = true;
            case "setanimation" -> { if (animation != null) setAnimation(value == null ? "" : value); return true; }
            case "setdefaultanimation" -> { defaultAnim = value == null || value.isEmpty() ? null : value; animationChanged = true; return true; }
            case "setplaybackrate" -> { if (animation != null) { playbackRate = (float) Variant.number(value); anchor(); } return true; }
            case "setcycle" -> { if (animation != null) { cycle = (float) Variant.number(value); anchor(); } return true; }
            default -> { return false; }
        }
        lookChanged();
        return true;
    }

    /** {@code PropSetAnim}: a sequence by name or activity; a model without one goes to sequence 0. */
    private void setAnimation(String name) {
        int found = lookupSequence(name);
        if (found >= 0) {
            setSequence(found);
            fire("onanimationbegun", null, null);
        } else {
            map.unhandled(this, "no sequence named " + name.toLowerCase(Locale.ROOT));
            sequence = 0;
            anchor();
        }
    }

    /** {@code LookupSequence}: by label, else a weighted pick among the activity's sequences. */
    private int lookupSequence(String name) {
        int byLabel = animation.label(name);
        return byLabel >= 0 ? byLabel : selectWeighted(animation.activity(name));
    }

    /**
     * {@code SelectWeightedSequence}: the current sequence when it is of the activity and weighs
     * less than zero, else a pick weighted by each sequence's activity weight.
     */
    private int selectWeighted(int[] candidates) {
        if (candidates.length == 0) return -1;
        for (int candidate : candidates) if (candidate == sequence && animation.sequence(candidate).activityWeight() < 0) return candidate;
        int total = 0;
        for (int candidate : candidates) total += Math.abs(animation.sequence(candidate).activityWeight());
        if (total <= 0) return candidates[0];
        int pick = LogicEntities.RANDOM.nextInt(total);
        for (int candidate : candidates) {
            pick -= Math.abs(animation.sequence(candidate).activityWeight());
            if (pick < 0) return candidate;
        }
        return candidates[candidates.length - 1];
    }

    /** {@code PropSetSequence}: toward {@code goal}, through the transition graph, and think. */
    private void setSequence(int goal) {
        goalSequence = goal;
        int[] next = gotoSequence(goal);
        if (next != null) {
            transitionDirection = next[1];
            finishSetSequence(next[0]);
        }
        if (nextThink == NEVER || nextThink <= map.time()) nextThink = map.time() + THINK_INTERVAL;
    }

    /**
     * {@code GotoSequence}: the sequence to play next on the way to {@code goal} and its direction,
     * or null to keep playing the current one.
     */
    private int[] gotoSequence(int goal) {
        int count = animation.sequences().size();
        if (sequence < 0 || sequence >= count || goal < 0 || goal >= count) return null;
        AnimationAsset.Sequence current = animation.sequence(sequence), target = animation.sequence(goal);
        if (current.entryNode() == 0 || target.entryNode() == 0) return new int[] {goal, 1};
        int endNode = current.exitNode();
        if (current.entryNode() != current.exitNode()) {
            if (playbackRate > 0 && cycle >= 0.999f) endNode = current.exitNode();
            else if (playbackRate < 0 && cycle <= 0.001f) endNode = current.entryNode();
            else return null;
        }
        if (endNode == target.entryNode()) return new int[] {goal, 1};
        int intern = animation.transition(endNode, target.entryNode());
        if (intern == 0) return new int[] {goal, 1};
        for (int i = 0; i < count; i++) {
            AnimationAsset.Sequence s = animation.sequence(i);
            if (s.entryNode() == endNode && s.exitNode() == intern) return new int[] {i, 1};
            if (s.nodeFlags() != 0 && s.exitNode() == endNode && s.entryNode() == intern) return new int[] {i, -1};
        }
        return null;
    }

    /** {@code FinishSetSequence}: from the start (or the end, played backwards). */
    private void finishSetSequence(int next) {
        cycle = 0;
        animTime = map.time();
        resetSequence(next);
        playbackRate = transitionDirection > 0 ? 1 : -1;
        cycle = transitionDirection > 0 ? 0 : 0.999f;
        // A looping sequence's pose is where it starts; one that stops settles where it ends.
        if (sequenceLoops) pose = next;
        anchor();
    }

    /** {@code ResetSequence}. */
    private void resetSequence(int next) {
        if (!sequenceLoops) cycle = 0;
        boolean changed = next != sequence;
        sequence = next;
        if (changed || !sequenceLoops) {
            sequenceLoops = animation.sequence(sequence).loops();
            playbackRate = 1;
            sequenceFinished = false;
            parity++;
        }
    }

    /** {@code AnimThink}. */
    @Override void think() {
        if (animation == null) return;
        if (randomAnimator && nextRandAnim < map.time()) {
            int idle = selectWeighted(animation.activity("ACT_IDLE"));
            if (idle >= 0) {
                resetSequence(idle);
                anchor();
                fire("onanimationbegun", null, null);
            }
            nextRandAnim = map.time() + random(minRandAnimTime, maxRandAnimTime);
        }
        if (((transitionDirection > 0 && cycle >= 0.999f) || (transitionDirection < 0 && cycle <= 0)) && !sequenceLoops) {
            if (sequence != goalSequence && goalSequence >= 0) {
                setSequence(goalSequence);
            } else {
                pose = sequence;
                fire("onanimationdone", null, null);
                if (randomAnimator) nextThink = map.time() + nextRandAnim + THINK_INTERVAL;
                else if (defaultAnim != null) setAnimation(defaultAnim);
            }
        } else {
            nextThink = map.time() + THINK_INTERVAL;
        }
        frameAdvance();
        if (nextThink == NEVER) anchor();
    }

    /** {@code StudioFrameAdvance}: the cycle on by the time since the last advance, at most 0.2 s of it. */
    private void frameAdvance() {
        double interval = Math.max(0, Math.min(MAX_ANIMTIME_INTERVAL, map.time() - animTime));
        if (interval <= 0.001) return;
        prevAnimTime = animTime;
        animTime = map.time();
        float next = cycle + (float) (interval * animation.sequence(sequence).cyclesPerSecond() * playbackRate);
        if (next < 0 || next >= 1) {
            if (sequenceLoops) next -= (int) next;
            else next = next < 0 ? 0 : 1;
            sequenceFinished = true;
        }
        cycle = next;
    }

    /** The clients are told the cycle anew from now: something other than time moved it. */
    private void anchor() {
        anchorCycle = cycle;
        anchorTime = map.time();
        anchorRate = playbackRate;
        animationChanged = true;
    }

    private static double random(double low, double high) {
        return high > low ? low + LogicEntities.RANDOM.nextDouble() * (high - low) : low;
    }

    @Override dev.theredja.src2mc.world.PropStates.State propState() {
        var base = super.propState();
        if (animation == null) return base;
        // A prop that does not think does not advance, whatever its rate.
        float rate = nextThink == NEVER ? 0 : anchorRate;
        return new dev.theredja.src2mc.world.PropStates.State(base.flags(), base.skin(), base.color(), sequence, anchorCycle, rate,
            anchorTime, parity, pose);
    }

    @Override String state() {
        String look = (noDraw ? "hidden" : "shown") + (notSolid ? ", not solid" : "") + ", skin " + skin;
        if (animation == null) return look;
        AnimationAsset.Sequence s = animation.sequence(sequence);
        return look + String.format(Locale.ROOT, ", sequence %d \"%s\" cycle %.3f rate %.2f%s%s", sequence, s.label(), cycle, playbackRate,
            s.loops() ? " looping" : "", defaultAnim == null ? "" : ", default \"" + defaultAnim + "\"");
    }

    @Override void save(CompoundTag tag) {
        super.save(tag);
        if (animation == null || !animationChanged) return;
        CompoundTag saved = new CompoundTag();
        saved.putInt("sequence", sequence);
        saved.putInt("goal", goalSequence);
        saved.putInt("direction", transitionDirection);
        saved.putInt("parity", parity);
        saved.putInt("pose", pose);
        saved.putFloat("cycle", cycle);
        saved.putFloat("rate", playbackRate);
        saved.putDouble("anim_time", animTime);
        saved.putDouble("prev_anim_time", prevAnimTime);
        saved.putBoolean("loops", sequenceLoops);
        saved.putBoolean("finished", sequenceFinished);
        if (defaultAnim != null) saved.putString("default", defaultAnim);
        saved.putDouble("next_random", nextRandAnim);
        saved.putFloat("anchor_cycle", anchorCycle);
        saved.putFloat("anchor_rate", anchorRate);
        saved.putDouble("anchor_time", anchorTime);
        tag.put("animation", saved);
    }

    @Override void load(CompoundTag tag) {
        super.load(tag);
        if (animation == null || !tag.contains("animation")) return;
        CompoundTag saved = tag.getCompound("animation");
        sequence = Math.max(0, Math.min(animation.sequences().size() - 1, saved.getInt("sequence")));
        goalSequence = saved.getInt("goal") < animation.sequences().size() ? saved.getInt("goal") : -1;
        transitionDirection = saved.getInt("direction");
        parity = saved.getInt("parity");
        pose = saved.getInt("pose") < animation.sequences().size() ? saved.getInt("pose") : -1;
        cycle = saved.getFloat("cycle");
        playbackRate = saved.getFloat("rate");
        animTime = saved.getDouble("anim_time");
        prevAnimTime = saved.getDouble("prev_anim_time");
        sequenceLoops = saved.getBoolean("loops");
        sequenceFinished = saved.getBoolean("finished");
        defaultAnim = saved.contains("default") ? saved.getString("default") : null;
        nextRandAnim = saved.getDouble("next_random");
        anchorCycle = saved.getFloat("anchor_cycle");
        anchorRate = saved.getFloat("anchor_rate");
        anchorTime = saved.getDouble("anchor_time");
        animationChanged = true;
    }
}
