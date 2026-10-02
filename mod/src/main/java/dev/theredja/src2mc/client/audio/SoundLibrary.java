package dev.theredja.src2mc.client.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Hands bundle sounds to Minecraft's own sound engine.
 *
 * <p>The engine plays a sound by asking its buffer library for {@code <namespace>:sounds/<path>.ogg}
 * and caches the decoded buffer under that name. The library only reads resource packs, so
 * before a bundle sound is played its decoded buffer is put into that cache under a name in a
 * namespace of its own; the engine then finds it there as if it were any other sound. Buffers
 * are content-addressed, so a name never needs replacing. The cache is the engine's: a sound
 * reload clears and frees it, and the next play simply puts the buffer back.
 *
 * <p>The cache has no accessor, so it is reached by reflection, by field type rather than name.
 * Should that ever fail, bundle sounds are switched off and the reason is logged once.
 */
final class SoundLibrary implements AutoCloseable {
    static final String NAMESPACE = "src2mc_audio";
    private static final HexFormat HEX = HexFormat.of();

    private final ExecutorService decoder = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "src2mc-sound-decoder"); thread.setDaemon(true); return thread;
    });
    private Field engineField, libraryField, cacheField;
    private boolean broken;
    private long decoded, failed;

    /** The name a sound plays under; {@code Sound#getPath} turns it into the cache key. */
    static ResourceLocation location(AudioTable.Sound sound) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, sound.contentId());
    }

    static ResourceLocation cacheKey(AudioTable.Sound sound) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "sounds/" + sound.contentId() + ".ogg");
    }

    boolean available() { return !broken && cache() != null; }

    /**
     * Makes sure the engine will find {@code sound} when it plays it, decoding it in the
     * background if it is not cached yet. Called on the render thread, where the engine plays.
     */
    boolean prepare(BundleManifest bundle, AudioTable.Sound sound) {
        Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache = cache();
        if (cache == null) return false;
        cache.computeIfAbsent(cacheKey(sound), ignored -> CompletableFuture.supplyAsync(() -> {
            Pcm pcm = decode(bundle, sound);
            return new SoundBuffer(pcm.data(), pcm.format());
        }, decoder));
        return true;
    }

    /** Decoded samples for a streamed sound, which the engine does not cache. */
    CompletableFuture<Pcm> pcm(BundleManifest bundle, AudioTable.Sound sound) {
        return CompletableFuture.supplyAsync(() -> decode(bundle, sound), decoder);
    }

    long decodedCount() { return decoded; }
    long failedCount() { return failed; }

    record Pcm(ByteBuffer data, AudioFormat format) {}

    private Pcm decode(BundleManifest bundle, AudioTable.Sound sound) {
        String path = sound.entryPath();
        try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
            BundleManifest.Entry entry = bundle.entries().stream().filter(candidate -> candidate.path().equals(path)).findFirst()
                .orElseThrow(() -> new IOException("sound absent from validated manifest"));
            var zipEntry = zip.getEntry(path);
            if (zipEntry == null || zipEntry.getSize() != entry.size() || entry.size() > Integer.MAX_VALUE) throw new IOException("sound changed after validation");
            byte[] bytes;
            try (var input = zip.getInputStream(zipEntry)) { bytes = input.readNBytes((int) entry.size() + 1); }
            if (bytes.length != entry.size() || !HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(entry.sha256()))
                throw new IOException("sound hash changed after validation");
            try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(bytes))) {
                Pcm pcm = new Pcm(stream.readAll(), stream.getFormat());
                synchronized (this) { decoded++; }
                return pcm;
            }
        } catch (Exception exception) {
            synchronized (this) { failed++; }
            Src2mc.LOGGER.warn("src2mc: could not decode {} ({})", path, sound.source(), exception);
            throw new IllegalStateException("failed to decode " + path, exception);
        }
    }

    /** Frees every bundle buffer the engine still holds, for a new generation. */
    void clear() {
        Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache = cache();
        if (cache == null) return;
        cache.entrySet().removeIf(entry -> {
            if (!entry.getKey().getNamespace().equals(NAMESPACE)) return false;
            entry.getValue().thenAccept(SoundBuffer::discardAlBuffer);
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache() {
        if (broken) return null;
        try {
            if (cacheField == null) {
                engineField = fieldOfType(SoundManager.class, SoundEngine.class);
                libraryField = fieldOfType(SoundEngine.class, SoundBufferLibrary.class);
                cacheField = fieldOfType(SoundBufferLibrary.class, Map.class);
            }
            Object engine = engineField.get(Minecraft.getInstance().getSoundManager());
            Object library = libraryField.get(engine);
            return (Map<ResourceLocation, CompletableFuture<SoundBuffer>>) cacheField.get(library);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            broken = true;
            Src2mc.LOGGER.error("src2mc: cannot reach Minecraft's sound buffer cache; map sounds are off", exception);
            return null;
        }
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) continue;
            if (found != null) throw new NoSuchFieldException(owner.getName() + " has more than one " + type.getSimpleName());
            found = field;
        }
        if (found == null) throw new NoSuchFieldException(owner.getName() + " has no " + type.getSimpleName());
        found.setAccessible(true);
        return found;
    }

    @Override public void close() { clear(); decoder.shutdownNow(); }
}
