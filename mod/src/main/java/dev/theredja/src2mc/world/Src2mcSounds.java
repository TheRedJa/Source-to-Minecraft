package dev.theredja.src2mc.world;

import dev.theredja.src2mc.Src2mc;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.DeferredSoundType;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The sounds map blocks make when walked on, hit, broken, placed or landed on. Each is its own
 * event so the client can recognise it and play what the map's own surface sounds like instead
 * (see {@code client.audio.SurfaceSounds}); {@code sounds.json} plays stone's for anything it
 * cannot place, and on a client without the map's bundle.
 */
public final class Src2mcSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Src2mc.MOD_ID);

    public static final Supplier<SoundEvent> STEP = register("surface.step");
    public static final Supplier<SoundEvent> BREAK = register("surface.break");
    public static final Supplier<SoundEvent> PLACE = register("surface.place");
    public static final Supplier<SoundEvent> HIT = register("surface.hit");
    public static final Supplier<SoundEvent> FALL = register("surface.fall");

    /** Stone's volume and pitch, so every fallback sounds exactly as the blocks did before. */
    public static final SoundType SURFACE = new DeferredSoundType(1.0F, 1.0F, BREAK, STEP, PLACE, HIT, FALL);

    private Src2mcSounds() {}

    static void register(IEventBus bus) { SOUNDS.register(bus); }

    private static Supplier<SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
