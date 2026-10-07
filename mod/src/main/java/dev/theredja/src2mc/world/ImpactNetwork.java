package dev.theredja.src2mc.world;

import dev.theredja.src2mc.Src2mc;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.Method;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Bullet impacts on the maps' surfaces: the server sees a projectile hit a block -- any of
 * Minecraft's projectiles, or a TacZ bullet when TacZ is installed -- and tells the players near
 * it, whose clients draw what Source draws there ({@code ImpactCallback}). Every hit is sent; a
 * client draws only what lands on a map surface.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID)
public final class ImpactNetwork {
    private ImpactNetwork() {}

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Players this far from a hit see it, in blocks. */
    private static final double RANGE = 96;

    /** A hit at {@code (x, y, z)} on a block's {@code face}, by something moving along {@code (dx, dy, dz)}. */
    public record ImpactPayload(double x, double y, double z, Direction face, float dx, float dy, float dz, boolean bullet) implements CustomPacketPayload {
        public static final Type<ImpactPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "impact"));
        public static final StreamCodec<FriendlyByteBuf, ImpactPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> {
                buffer.writeDouble(p.x); buffer.writeDouble(p.y); buffer.writeDouble(p.z); buffer.writeByte(p.face.get3DDataValue());
                buffer.writeFloat(p.dx); buffer.writeFloat(p.dy); buffer.writeFloat(p.dz); buffer.writeBoolean(p.bullet);
            },
            buffer -> {
                double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
                int face = buffer.readUnsignedByte();
                if (face > 5) throw new DecoderException("invalid src2mc impact face " + face);
                return new ImpactPayload(x, y, z, Direction.from3DDataValue(face), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean());
            });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(ImpactPayload.TYPE, ImpactPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.particles.Impacts.apply(payload));
    }

    @SubscribeEvent
    static void projectileHit(ProjectileImpactEvent event) {
        Entity projectile = event.getProjectile();
        if (projectile.level().isClientSide()) return;
        send(projectile.level(), event.getRayTraceResult(), projectile.getDeltaMovement(), false);
    }

    private static void send(Level level, HitResult result, Vec3 motion, boolean bullet) {
        if (!(level instanceof ServerLevel server) || !(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        Vec3 at = hit.getLocation();
        PacketDistributor.sendToPlayersNear(server, null, at.x, at.y, at.z, RANGE,
            new ImpactPayload(at.x, at.y, at.z, hit.getDirection(), (float) motion.x, (float) motion.y, (float) motion.z, bullet));
    }

    private static boolean taczHooked;

    /**
     * TacZ's {@code AmmoHitBlockEvent} (1.1.8), fired on the server when a bullet hits a block. Read
     * by reflection so the mod runs without TacZ.
     */
    @SubscribeEvent
    static void hookTacz(ServerAboutToStartEvent event) {
        if (taczHooked || !ModList.get().isLoaded("tacz")) return;
        taczHooked = true;
        try {
            @SuppressWarnings("unchecked")
            Class<Event> type = (Class<Event>) Class.forName("com.tacz.guns.api.event.server.AmmoHitBlockEvent");
            Method levelOf = type.getMethod("getLevel"), hitOf = type.getMethod("getHitResult"), ammoOf = type.getMethod("getAmmo");
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, type, hit -> {
                try {
                    Entity ammo = (Entity) ammoOf.invoke(hit);
                    send((Level) levelOf.invoke(hit), (HitResult) hitOf.invoke(hit), ammo.getDeltaMovement(), true);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.debug("src2mc could not read a TacZ bullet hit", e);
                }
            });
            LOGGER.info("src2mc: TacZ bullet impacts hooked");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("src2mc: TacZ is installed but its AmmoHitBlockEvent was not found; its bullets make no Source impacts", e);
        }
    }
}
