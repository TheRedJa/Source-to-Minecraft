package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleManifest;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

/**
 * One bundle sound playing through Minecraft's engine, with Source's distance falloff applied
 * every tick instead of Minecraft's.
 *
 * <p>How it is placed follows Source: a mono sound is placed in the world; a stereo one plays as
 * stereo, unpanned, but still fades with distance, which is what Source does with stereo files
 * not marked for spatial stereo (the converter has already mixed down the ones that are); and
 * a sound at sound level 0 is heard everywhere at full volume.
 *
 * <p>A sound that loops from its first frame loops in OpenAL. One whose loop starts later is
 * streamed, so the part before the loop point plays once; Minecraft has only a few streaming
 * channels, and Source files almost always loop whole.
 */
final class SourceSound extends AbstractSoundInstance implements TickableSoundInstance {
    static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath("src2mc", "map_sound");
    private static final WeighedSoundEvents EVENTS = new WeighedSoundEvents(EVENT, null);

    private final SoundLibrary library;
    private final BundleManifest bundle;
    private final AudioTable.Sound asset;
    private final Vec3 source;
    private final double soundLevel;
    private final boolean placed;
    private final boolean streamed;
    private float level = 1.0F;
    private boolean stopped;

    /**
     * @param position world position, or null for a sound heard everywhere
     * @param soundLevel Source decibels; 0 for no falloff
     * @param loop whether a sound whose file loops may loop here; one-shots never do
     */
    SourceSound(SoundLibrary library, BundleManifest bundle, AudioTable.Sound asset, SoundSource category,
                Vec3 position, double soundLevel, float level, float pitch, boolean loop) {
        super(EVENT, category, RandomSource.create());
        this.library = library;
        this.bundle = bundle;
        this.asset = asset;
        this.source = position;
        this.soundLevel = position == null ? 0.0 : soundLevel;
        this.placed = position != null && asset.channels() == 1 && soundLevel > 0;
        this.looping = loop && asset.loops();
        this.streamed = looping && asset.loopStart() > 0;
        this.pitch = pitch;
        this.level = level;
        this.attenuation = Attenuation.NONE;
        this.relative = !placed;
        updatePlacement();
    }

    /** The loudness before falloff, 0..1; a soundscape fades it. */
    void setLevel(float level) { this.level = level; }

    /** The pitch factor, 1 unchanged; the engine picks it up on the next tick. */
    void setPitch(float pitch) { this.pitch = pitch; }
    float level() { return level; }
    AudioTable.Sound asset() { return asset; }

    void stopPlaying() { stopped = true; }

    /** Puts its buffer where the engine will find it. False when bundle sounds are unavailable. */
    boolean prepare() { return streamed || library.prepare(bundle, asset); }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        // Volume and pitch are this instance's own; the sound adds nothing.
        sound = new Sound(SoundLibrary.location(asset), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
            Sound.Type.FILE, streamed, false, 16);
        return EVENTS;
    }

    @Override public boolean canStartSilent() { return true; }
    @Override public boolean isStopped() { return stopped; }

    @Override
    public void tick() { updatePlacement(); }

    private void updatePlacement() {
        double gain = 1.0;
        if (source != null && soundLevel > 0) {
            Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            gain = SourceFalloff.gain(soundLevel, listener.distanceTo(source));
        }
        volume = (float) (level * gain);
        if (placed) { x = source.x; y = source.y; z = source.z; }
        else { x = 0; y = 0; z = 0; }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return library.pcm(bundle, asset).thenApply(pcm -> new LoopStream(pcm.data(), pcm.format(), asset.loopStart()));
    }

    /** Plays once from the start, then loops from the loop point for good. */
    private static final class LoopStream implements AudioStream {
        private final ByteBuffer pcm;
        private final AudioFormat format;
        private final int loopStartByte;
        private int at;

        LoopStream(ByteBuffer pcm, AudioFormat format, long loopStartFrame) {
            this.pcm = pcm;
            this.format = format;
            int frameBytes = format.getChannels() * (format.getSampleSizeInBits() / 8);
            this.loopStartByte = (int) Math.min(loopStartFrame * frameBytes, Math.max(0, pcm.limit() - frameBytes));
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer out = org.lwjgl.BufferUtils.createByteBuffer(size);
            while (out.hasRemaining()) {
                if (at >= pcm.limit()) at = loopStartByte;
                int count = Math.min(out.remaining(), pcm.limit() - at);
                out.put(out.position(), pcm, at, count);
                out.position(out.position() + count);
                at += count;
            }
            return out.flip();
        }

        @Override public void close() {}
    }
}
