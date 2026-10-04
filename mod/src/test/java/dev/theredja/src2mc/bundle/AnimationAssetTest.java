package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AnimationAssetTest {
    /**
     * Two bones, the second a block up its parent; one vertex on each. Sequence "turn" has two
     * frames: the parent at rest, then a quarter turn about y.
     */
    private static byte[] twoBones(int vertexBone) {
        ByteBuffer out = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'S', '2', 'A', 'N', 'I', 'M', 0, 0}).putInt(1).putInt(2).putInt(1).putInt(1).putInt(0);
        // Bone 0 at the origin; bone 1 a block up; binds undo their rest places.
        out.putInt(-1).putFloat(0).putFloat(0).putFloat(0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 32767);
        for (float v : new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}) out.putFloat(v);
        out.putInt(0).putFloat(0).putFloat(1).putFloat(0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 32767);
        for (float v : new float[] {1, 0, 0, 0, 0, 1, 0, -1, 0, 0, 1, 0}) out.putFloat(v);
        out.put((byte) 1).put((byte) vertexBone).put((byte) 0).put((byte) 0).putFloat(1).putFloat(0).putFloat(0);
        byte[] name = "turn".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.putShort((short) name.length).put(name).putShort((short) 0);
        out.putInt(1).putInt(0).putFloat(0.2f).putFloat(0.2f).putInt(0).putInt(0).putInt(0).putFloat(1).putFloat(1).putFloat(1).putInt(2);
        // Bone 0: still position, turning rotation; bone 1: still at a block up.
        out.put((byte) 0).put((byte) 1).putFloat(0).putFloat(0).putFloat(0);
        out.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 32767);
        out.putShort((short) 0).putShort((short) 23170).putShort((short) 0).putShort((short) 23170);
        out.put((byte) 0).put((byte) 0).putFloat(0).putFloat(1).putFloat(0);
        out.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 32767);
        return Arrays.copyOf(out.array(), out.position());
    }

    @Test void bonesComposeThroughTheirParentsAndFramesBlend() throws Exception {
        AnimationAsset asset = AnimationAsset.decode(twoBones(1), 1);
        assertEquals(0, asset.label("TURN"));
        float[] positions = new float[6], rotations = new float[8], skin = new float[24];
        asset.localPose(0, 0, positions, rotations);
        asset.skinning(positions, rotations, skin);
        // At rest every skinning transform is the identity.
        for (int bone = 0; bone < 2; bone++) for (int i = 0; i < 12; i++) assertEquals(i % 5 == 0 ? 1 : 0, skin[bone * 12 + i], 1e-4, "bone " + bone);
        asset.localPose(0, 1, positions, rotations);
        asset.skinning(positions, rotations, skin);
        // A quarter turn about y carries x onto -z; the child turns with its parent about the parent's origin.
        float x = 1, y = 1, z = 0;
        float ox = skin[12] * x + skin[13] * y + skin[14] * z + skin[15], oz = skin[20] * x + skin[21] * y + skin[22] * z + skin[23];
        assertEquals(0, ox, 1e-4);
        assertEquals(-1, oz, 1e-4);
        // Halfway, QuaternionBlend lands on an eighth of a turn.
        asset.localPose(0, 0.5f, positions, rotations);
        assertEquals(Math.sin(Math.PI / 8), rotations[1], 1e-4);
    }

    @Test void bindingsAndCountsAreChecked() {
        assertThrows(BundleValidationException.class, () -> AnimationAsset.decode(twoBones(1), 2), "vertex count must match the mesh");
        assertThrows(BundleValidationException.class, () -> AnimationAsset.decode(twoBones(2), 1), "bone out of range");
        byte[] trailing = Arrays.copyOf(twoBones(1), twoBones(1).length + 1);
        assertThrows(BundleValidationException.class, () -> AnimationAsset.decode(trailing, 1));
    }
}
