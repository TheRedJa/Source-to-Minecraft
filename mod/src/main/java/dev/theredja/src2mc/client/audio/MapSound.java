package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.world.MapPlacement;
import net.minecraft.world.phys.Vec3;

/**
 * A placed map with sound, and what it takes to turn its map-local positions into world ones.
 * Never a hash key: it holds a {@link BundleMap}, whose hash walks the whole map.
 */
record MapSound(MapPlacement placement, BundleManifest bundle, BundleMap map, PropGround props) {
    AudioTable audio() { return map.audio(); }

    Vec3 world(double x, double y, double z) {
        return new Vec3(x + placement.translation().getX(), y + placement.translation().getY(), z + placement.translation().getZ());
    }

    Vec3 world(double[] position) { return position == null ? null : world(position[0], position[1], position[2]); }

    Vec3 local(Vec3 world) {
        return world.subtract(placement.translation().getX(), placement.translation().getY(), placement.translation().getZ());
    }

    AudioTable.Sound sound(int index) { return map.audio().sounds().get(index); }
}
