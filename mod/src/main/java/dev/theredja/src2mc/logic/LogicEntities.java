package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import net.minecraft.nbt.CompoundTag;

/**
 * Source's pure logic entities, after the SDK's {@code logicrelay.cpp}, {@code logicauto.cpp},
 * {@code logicentities.cpp} and {@code filters.cpp}, and the factory that picks each entity's
 * class. Behaviour follows the SDK line by line where it matters to a chain: what fires, with which
 * activator, and what is ignored while disabled.
 */
final class LogicEntities {
    private LogicEntities() {}

    static final RandomGenerator RANDOM = RandomGenerator.of("L64X128MixRandom");

    static LogicEntity create(MapLogic map, int index, LogicTable.Entity entity) {
        return switch (entity.classname()) {
            case "logic_relay" -> new Relay(map, index, entity);
            case "logic_auto" -> new Auto(map, index, entity);
            case "logic_branch" -> new Branch(map, index, entity);
            case "logic_branch_listener" -> new BranchListener(map, index, entity);
            case "logic_case" -> new Case(map, index, entity);
            case "logic_compare" -> new Compare(map, index, entity);
            case "math_counter" -> new Counter(map, index, entity);
            case "logic_timer" -> new Timer(map, index, entity);
            case "filter_activator_name", "filter_activator_class", "filter_multi" -> new Filter(map, index, entity);
            case "func_instance_io_proxy" -> new InstanceProxy(map, index, entity);
            case "trigger_once", "trigger_multiple" -> new Triggers.Trigger(map, index, entity);
            case "trigger_changelevel" -> new Triggers.ChangeLevel(map, index, entity);
            case "func_button", "func_rot_button", "momentary_rot_button" -> new Movers.Button(map, index, entity);
            case "func_brush" -> new Movers.Brush(map, index, entity);
            case "prop_dynamic", "prop_dynamic_override", "prop_dynamic_ornament" -> new Movers.DynamicProp(map, index, entity);
            case "func_tracktrain" -> new Trains.TrackTrain(map, index, entity);
            case "path_track" -> new Trains.PathTrack(map, index, entity);
            case "func_rotating" -> new Trains.Rotating(map, index, entity);
            case "func_door", "func_door_rotating", "func_movelinear", "infra_button", "prop_door_rotating" -> new Movers.Door(map, index, entity);
            case "ambient_generic" -> new SoundEntities.Ambient(map, index, entity);
            case "env_soundscape" -> new SoundEntities.Soundscape(map, index, entity);
            case "infra_music" -> new SoundEntities.Music(map, index, entity);
            case "logic_choreographed_scene" -> new SoundEntities.Scene(map, index, entity);
            default -> new LogicEntity(map, index, entity);
        };
    }

    /** {@code CLogicRelay}. */
    static final class Relay extends LogicEntity {
        private static final int REMOVE_ON_FIRE = 1, ALLOW_FAST_RETRIGGER = 2;
        private boolean disabled, waitForRefire;

        Relay(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { disabled = flag("startdisabled"); }

        @Override void activate() { if (hasOutput("onspawn")) nextThink = map.time() + 0.01; }

        @Override void think() {
            fire("onspawn", this, null);
            if (hasSpawnFlags(REMOVE_ON_FIRE)) remove();
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> disabled = false;
                case "enablerefire" -> waitForRefire = false;
                case "disable" -> disabled = true;
                case "toggle" -> disabled = !disabled;
                case "cancelpending" -> { map.cancel(this); waitForRefire = false; }
                case "trigger" -> {
                    if (disabled || waitForRefire) return true;
                    fire("ontrigger", activator, null);
                    if (hasSpawnFlags(REMOVE_ON_FIRE)) remove();
                    else if (!hasSpawnFlags(ALLOW_FAST_RETRIGGER)) {
                        // Source waits out the longest delay of OnTrigger before allowing another trigger.
                        waitForRefire = true;
                        map.queue(maxDelay("ontrigger") + 0.001, null, this, "EnableRefire", null, this, this);
                    }
                }
                default -> { return false; }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("disabled", disabled);
            tag.putBoolean("wait_for_refire", waitForRefire);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            disabled = tag.getBoolean("disabled");
            waitForRefire = tag.getBoolean("wait_for_refire");
        }

        @Override String state() { return (disabled ? "disabled" : "enabled") + (waitForRefire ? ", waiting to refire" : ""); }
    }

    /** {@code CLogicAuto}: fires 0.2 s after the map activates, telling a new game from a level change. */
    static final class Auto extends LogicEntity {
        private static final int FIRE_ONCE = 1;

        Auto(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void activate() { nextThink = map.time() + 0.2; }

        @Override void think() {
            // A globalstate gate needs env_global, which is not run here; such an auto fires as if it were on.
            if (map.loadType() == MapLogic.LoadType.TRANSITION) fire("onmaptransition", null, null);
            else fire("onnewgame", null, null);
            fire("onmapspawn", null, null);
            if (hasSpawnFlags(FIRE_ONCE)) remove();
        }
    }

    /** {@code CLogicBranch}; listeners hear of every change through a direct event, as in Source. */
    static final class Branch extends LogicEntity {
        private boolean value;
        final List<LogicEntity> listeners = new ArrayList<>();

        Branch(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { value = flag("initialvalue"); }

        boolean value() { return value; }

        @Override boolean accept(String input, String in, Actor activator, LogicEntity caller) {
            switch (input) {
                case "setvalue" -> update(Variant.bool(in), activator, false);
                case "setvaluetest" -> update(Variant.bool(in), activator, true);
                case "toggle" -> update(!value, activator, false);
                case "toggletest" -> update(!value, activator, true);
                case "test" -> update(value, activator, true);
                default -> { return false; }
            }
            return true;
        }

        private void update(boolean newValue, Actor activator, boolean test) {
            if (value != newValue) {
                value = newValue;
                for (LogicEntity listener : listeners) map.queue(0, null, listener, "_OnLogicBranchChanged", null, this, this);
            }
            if (test) fire(value ? "ontrue" : "onfalse", activator, null);
        }

        @Override void remove() {
            for (LogicEntity listener : listeners) map.queue(0, null, listener, "_OnLogicBranchRemoved", null, this, this);
            super.remove();
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putBoolean("value", value); }
        @Override void load(CompoundTag tag) { super.load(tag); value = tag.getBoolean("value"); }
        @Override String state() { return value ? "true" : "false"; }
    }

    /** {@code CLogicBranchList}. */
    static final class BranchListener extends LogicEntity {
        private enum Last { NOT_INIT, ALL_TRUE, ALL_FALSE, MIXED }
        private final List<Branch> branches = new ArrayList<>();
        private Last last = Last.NOT_INIT;

        BranchListener(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void activate() {
            branches.clear();
            for (int i = 1; i <= 16; i++) {
                String name = key(String.format(Locale.ROOT, "branch%02d", i));
                if (name == null || name.isEmpty()) continue;
                for (LogicEntity entity : map.find(name, this, null, null)) {
                    if (entity instanceof Branch branch && !branches.contains(branch)) {
                        if (!branch.listeners.contains(this)) branch.listeners.add(this);
                        branches.add(branch);
                    }
                }
            }
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "test" -> { last = Last.NOT_INIT; test(activator); }
                case "_onlogicbranchchanged" -> test(activator);
                case "_onlogicbranchremoved" -> { branches.remove(activator); test(activator); }
                default -> { return false; }
            }
            return true;
        }

        private void test(Actor activator) {
            boolean oneTrue = false, oneFalse = false;
            for (Branch branch : branches) {
                if (!branch.removed && branch.value()) oneTrue = true;
                else oneFalse = true;
            }
            Last now = oneTrue && !oneFalse ? Last.ALL_TRUE : oneFalse && !oneTrue ? Last.ALL_FALSE : Last.MIXED;
            if (now == last) return;
            last = now;
            fire(switch (now) { case ALL_TRUE -> "onalltrue"; case ALL_FALSE -> "onallfalse"; default -> "onmixed"; }, activator, null);
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putString("last", last.name()); }
        @Override void load(CompoundTag tag) { super.load(tag); if (!tag.getString("last").isEmpty()) last = Last.valueOf(tag.getString("last")); }
    }

    /** {@code CLogicCase}. */
    static final class Case extends LogicEntity {
        private static final int CASES = 16;
        private final int[] shuffle = new int[CASES];
        private int shuffleCount, lastShuffle = -1;

        Case(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        private String caseValue(int i) { return key(String.format(Locale.ROOT, "case%02d", i + 1)); }
        private String caseOutput(int i) { return String.format(Locale.ROOT, "oncase%02d", i + 1); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "invalue" -> {
                    String in = value == null ? "" : value;
                    for (int i = 0; i < CASES; i++) {
                        String option = caseValue(i);
                        if (option != null && !option.isEmpty() && option.equalsIgnoreCase(in)) { fire(caseOutput(i), activator, null); return true; }
                    }
                    fire("ondefault", activator, value);
                }
                case "pickrandom" -> {
                    int[] map = new int[CASES];
                    int count = build(map);
                    if (count > 0) fire(caseOutput(map[RANDOM.nextInt(count)]), activator, null);
                }
                case "pickrandomshuffle" -> pickShuffle(activator);
                default -> { return false; }
            }
            return true;
        }

        private int build(int[] map) {
            int count = 0;
            for (int i = 0; i < CASES; i++) if (hasOutput(caseOutput(i))) map[count++] = i;
            return count;
        }

        private void pickShuffle(Actor activator) {
            int count = shuffleCount;
            if (count == 0) {
                count = shuffleCount = build(shuffle);
                if (shuffleCount > 1 && lastShuffle != -1) {
                    for (int i = 0; i < shuffleCount; i++) {
                        if (shuffle[i] == lastShuffle) {
                            int swap = shuffle[i];
                            shuffle[i] = shuffle[count - 1];
                            shuffle[count - 1] = swap;
                            count--;
                            break;
                        }
                    }
                }
            }
            if (count <= 0) return;
            int pick = RANDOM.nextInt(count);
            int chosen = shuffle[pick];
            fire(caseOutput(chosen), activator, null);
            shuffle[pick] = shuffle[shuffleCount - 1];
            shuffleCount--;
            lastShuffle = chosen;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putIntArray("shuffle", java.util.Arrays.copyOf(shuffle, shuffleCount));
            tag.putInt("last_shuffle", lastShuffle);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            int[] saved = tag.getIntArray("shuffle");
            shuffleCount = Math.min(saved.length, CASES);
            System.arraycopy(saved, 0, shuffle, 0, shuffleCount);
            lastShuffle = tag.contains("last_shuffle") ? tag.getInt("last_shuffle") : -1;
        }
    }

    /** {@code CLogicCompare}. */
    static final class Compare extends LogicEntity {
        private double value, compareValue;

        Compare(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            value = (float) number("initialvalue", 0);
            compareValue = (float) number("comparevalue", 0);
        }

        @Override boolean accept(String input, String in, Actor activator, LogicEntity caller) {
            switch (input) {
                case "setvalue" -> value = (float) Variant.number(in);
                case "setvaluecompare" -> { value = (float) Variant.number(in); compare(activator); }
                case "setcomparevalue" -> compareValue = (float) Variant.number(in);
                case "compare" -> compare(activator);
                default -> { return false; }
            }
            return true;
        }

        private void compare(Actor activator) {
            String out = Variant.of(value);
            if (value == compareValue) {
                fire("onequalto", activator, out);
            } else {
                fire("onnotequalto", activator, out);
                fire(value > compareValue ? "ongreaterthan" : "onlessthan", activator, out);
            }
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putDouble("value", value); tag.putDouble("compare", compareValue); }
        @Override void load(CompoundTag tag) { super.load(tag); value = tag.getDouble("value"); compareValue = tag.getDouble("compare"); }
        @Override String state() { return Variant.of(value) + " vs " + Variant.of(compareValue); }
    }

    /** {@code CMathCounter}; values are single precision as in Source, so comparisons match. */
    static final class Counter extends LogicEntity {
        private float value, min, max;
        private boolean disabled, hitMin, hitMax;

        Counter(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            value = Variant.integer(key("startvalue"));
            min = (float) number("min", 0);
            max = (float) number("max", 0);
            disabled = flag("startdisabled");
            if (min > max) { float swap = max; max = min; min = swap; }
            if (min != 0 || max != 0) value = Math.max(min, Math.min(max, value));
        }

        @Override boolean accept(String input, String in, Actor activator, LogicEntity caller) {
            float operand = (float) Variant.number(in);
            switch (input) {
                case "add" -> { if (!disabled) update(activator, value + operand); }
                case "subtract" -> { if (!disabled) update(activator, value - operand); }
                case "multiply" -> { if (!disabled) update(activator, value * operand); }
                case "divide" -> { if (!disabled) update(activator, operand != 0 ? value / operand : value); }
                case "setvalue" -> { if (!disabled) update(activator, operand); }
                case "setvaluenofire" -> { if (!disabled) value = min != 0 || max != 0 ? Math.max(min, Math.min(max, operand)) : operand; }
                case "sethitmax" -> { max = operand; if (max < min) min = max; update(activator, value); }
                case "sethitmin" -> { min = operand; if (max < min) max = min; update(activator, value); }
                case "getvalue" -> fire("ongetvalue", activator, caller, Variant.of(value), 0);
                case "enable" -> disabled = false;
                case "disable" -> disabled = true;
                default -> { return false; }
            }
            return true;
        }

        private void update(Actor activator, float newValue) {
            if (min != 0 || max != 0) {
                if (newValue >= max) { if (!hitMax) { hitMax = true; fire("onhitmax", activator, null); } }
                else hitMax = false;
                if (newValue <= min) { if (!hitMin) { hitMin = true; fire("onhitmin", activator, null); } }
                else hitMin = false;
                newValue = Math.max(min, Math.min(max, newValue));
            }
            value = newValue;
            fire("outvalue", activator, Variant.of(value));
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putFloat("value", value); tag.putFloat("min", min); tag.putFloat("max", max);
            tag.putBoolean("disabled", disabled); tag.putBoolean("hit_min", hitMin); tag.putBoolean("hit_max", hitMax);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            value = tag.getFloat("value"); min = tag.getFloat("min"); max = tag.getFloat("max");
            disabled = tag.getBoolean("disabled"); hitMin = tag.getBoolean("hit_min"); hitMax = tag.getBoolean("hit_max");
        }

        @Override String state() { return Variant.of(value) + " in [" + Variant.of(min) + ", " + Variant.of(max) + "]" + (disabled ? ", disabled" : ""); }
    }

    /** {@code CTimerEntity}. */
    static final class Timer extends LogicEntity {
        private static final int UP_DOWN = 1;
        private static final double MIN_INTERVAL = 0.01;
        private boolean disabled, upDown, useRandom;
        private double refire, lower, upper;

        Timer(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            useRandom = Variant.integer(key("userandomtime")) != 0;
            lower = number("lowerrandombound", 0);
            upper = number("upperrandombound", 0);
            refire = number("refiretime", 0);
            if (!useRandom && refire < MIN_INTERVAL) refire = MIN_INTERVAL;
            if (Variant.integer(key("startdisabled")) == 0 && (refire > 0 || useRandom)) enable();
            else disable();
        }

        private void reset() {
            if (disabled) return;
            if (useRandom) refire = lower + RANDOM.nextDouble() * (upper - lower);
            nextThink = map.time() + refire;
        }

        private void enable() { disabled = false; reset(); }
        private void disable() { disabled = true; nextThink = NEVER; }

        @Override void think() { fireTimer(); }

        private void fireTimer() {
            if (disabled) return;
            if (hasSpawnFlags(UP_DOWN)) {
                fire(upDown ? "ontimerhigh" : "ontimerlow", this, null);
                upDown = !upDown;
            } else {
                fire("ontimer", this, null);
            }
            reset();
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> enable();
                case "disable" -> disable();
                case "toggle" -> { if (disabled) enable(); else disable(); }
                case "firetimer" -> fireTimer();
                case "refiretime" -> {
                    double interval = Math.max(MIN_INTERVAL, Variant.number(value));
                    if (refire != interval) { refire = interval; reset(); }
                }
                case "resettimer" -> reset();
                case "addtotimer" -> { if (!disabled) nextThink += Variant.number(value); }
                case "subtractfromtimer" -> {
                    if (disabled) return true;
                    double amount = Variant.number(value);
                    nextThink = nextThink - map.time() <= amount ? map.time() : nextThink - amount;
                }
                case "userandomtime" -> useRandom = Variant.integer(value) != 0;
                case "lowerrandombound" -> lower = Variant.number(value);
                case "upperrandombound" -> upper = Variant.number(value);
                default -> { return false; }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("disabled", disabled); tag.putBoolean("up_down", upDown); tag.putBoolean("use_random", useRandom);
            tag.putDouble("refire", refire); tag.putDouble("lower", lower); tag.putDouble("upper", upper);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            disabled = tag.getBoolean("disabled"); upDown = tag.getBoolean("up_down"); useRandom = tag.getBoolean("use_random");
            refire = tag.getDouble("refire"); lower = tag.getDouble("lower"); upper = tag.getDouble("upper");
        }

        @Override String state() { return disabled ? "disabled" : "every " + Variant.of(refire) + "s"; }
    }

    /**
     * {@code filter_activator_name}, {@code filter_activator_class} and {@code filter_multi}. Name
     * and class match ignoring case, with a trailing {@code *} as a wildcard.
     */
    static final class Filter extends LogicEntity {
        Filter(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        boolean passes(LogicEntity caller, Actor actor) {
            boolean result = passesImpl(caller, actor);
            return flag("negated") != result;
        }

        private boolean passesImpl(LogicEntity caller, Actor actor) {
            if (actor == null) return false;
            return switch (classname) {
                case "filter_activator_name" -> matches(actor.name(), key("filtername", ""));
                case "filter_activator_class" -> matches(actor.classname(), key("filterclass", ""));
                default -> multi(caller, actor);
            };
        }

        /** {@code CFilterMultiple}: AND (type 0) or OR (type 1) of up to five filters. */
        private boolean multi(LogicEntity caller, Actor actor) {
            boolean or = Variant.integer(key("filtertype")) == 1;
            for (int i = 1; i <= 5; i++) {
                LogicEntity entity = map.findFirst(key(String.format(Locale.ROOT, "filter%02d", i), ""), this, null, null);
                if (!(entity instanceof Filter filter)) continue;
                boolean passes = filter.passes(caller, actor);
                if (or && passes) return true;
                if (!or && !passes) return false;
            }
            return !or;
        }

        static boolean matches(String value, String pattern) {
            if (pattern.endsWith("*")) return value.toLowerCase(Locale.ROOT).startsWith(pattern.substring(0, pattern.length() - 1).toLowerCase(Locale.ROOT));
            return value.equalsIgnoreCase(pattern);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("testactivator")) return false;
            fire(passes(caller, activator) ? "onpass" : "onfail", activator, null);
            return true;
        }
    }

    /**
     * {@code func_instance_io_proxy}: VBSP routes an instance's outputs through it; an
     * {@code OnProxyRelay<n>} input fires the same-named output.
     */
    static final class InstanceProxy extends LogicEntity {
        InstanceProxy(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.startsWith("onproxyrelay")) return false;
            fire(input, activator, value);
            return true;
        }
    }
}
