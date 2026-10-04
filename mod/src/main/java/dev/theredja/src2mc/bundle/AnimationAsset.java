package dev.theredja.src2mc.bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An animated prop's skeleton and sequences (format.md section 19), decoded once when the bundle
 * is validated: both sides need it, the server to play a sequence by {@code CDynamicProp}'s rules,
 * the client to pose the mesh. Positions and rotations are in the model-local block space of the
 * runtime mesh, so composing them needs no conversion.
 *
 * <p>Equality is identity, like the other large tables.
 */
public final class AnimationAsset {
    public static final int STUDIO_LOOPING = 0x0001, STUDIO_SNAP = 0x0002, STUDIO_DELTA = 0x0004, STUDIO_REALTIME = 0x0100;
    private static final byte[] MAGIC = {'S', '2', 'A', 'N', 'I', 'M', 0, 0};
    private static final int MAX_BONES = 256, MAX_SEQUENCES = 4096, MAX_FRAMES = 65536, MAX_NODES = 255, MAX_NAME = 1024;

    private final int[] parents;
    private final float[] restPositions, restRotations, binds;
    private final byte[] counts, bones;
    private final float[] weights;
    private final int nodes;
    private final byte[] transitions;
    private final List<Sequence> sequences;

    private AnimationAsset(int[] parents, float[] restPositions, float[] restRotations, float[] binds, byte[] counts, byte[] bones,
                           float[] weights, int nodes, byte[] transitions, List<Sequence> sequences) {
        this.parents = parents; this.restPositions = restPositions; this.restRotations = restRotations; this.binds = binds;
        this.counts = counts; this.bones = bones; this.weights = weights; this.nodes = nodes; this.transitions = transitions;
        this.sequences = List.copyOf(sequences);
    }

    /**
     * One sequence. {@code frames} is 0 for one whose frames the bundle leaves out, which the map
     * cannot reach; it still has its name and timing, so looking it up finds what Source would.
     */
    public static final class Sequence {
        private final String label, activity;
        private final int activityWeight, flags, entryNode, exitNode, nodeFlags, frames;
        private final float fadeIn, fadeOut, cyclesPerSecond;
        private final float[] boneWeights;
        /** Per bone: 3 or {@code 3 * frames} positions, 4 or {@code 4 * frames} unit rotations. */
        private final float[][] positions, rotations;

        Sequence(String label, String activity, int activityWeight, int flags, float fadeIn, float fadeOut, int entryNode, int exitNode,
                 int nodeFlags, float cyclesPerSecond, float[] boneWeights, int frames, float[][] positions, float[][] rotations) {
            this.label = label; this.activity = activity; this.activityWeight = activityWeight; this.flags = flags; this.fadeIn = fadeIn;
            this.fadeOut = fadeOut; this.entryNode = entryNode; this.exitNode = exitNode; this.nodeFlags = nodeFlags;
            this.cyclesPerSecond = cyclesPerSecond; this.boneWeights = boneWeights; this.frames = frames; this.positions = positions;
            this.rotations = rotations;
        }

        public String label() { return label; }
        public String activity() { return activity; }
        public int activityWeight() { return activityWeight; }
        public int flags() { return flags; }
        public boolean loops() { return (flags & STUDIO_LOOPING) != 0; }
        public float fadeIn() { return fadeIn; }
        public float fadeOut() { return fadeOut; }
        public int entryNode() { return entryNode; }
        public int exitNode() { return exitNode; }
        public int nodeFlags() { return nodeFlags; }
        /** {@code Studio_CPS}: cycles per second at playback rate 1. */
        public float cyclesPerSecond() { return cyclesPerSecond; }
        public float boneWeight(int bone) { return boneWeights[bone]; }
        public int frames() { return frames; }
    }

    public int boneCount() { return parents.length; }
    public int parent(int bone) { return parents[bone]; }
    public int vertexCount() { return counts.length; }
    public List<Sequence> sequences() { return sequences; }
    public Sequence sequence(int index) { return sequences.get(index); }

    /** How many bones move vertex {@code vertex}, 0 to 3. */
    public int boneCount(int vertex) { return counts[vertex]; }
    public int bone(int vertex, int slot) { return bones[vertex * 3 + slot] & 0xFF; }
    public float weight(int vertex, int slot) { return weights[vertex * 3 + slot]; }

    /** The transition graph's node count; 0 for none. */
    public int nodes() { return nodes; }

    /** {@code GetTransition}: the node to go through from {@code from} towards {@code to}, both 1-based; 0 for none. */
    public int transition(int from, int to) {
        if (from < 1 || to < 1 || from > nodes || to > nodes) return 0;
        return transitions[(from - 1) * nodes + (to - 1)] & 0xFF;
    }

    /** {@code LookupSequence}: the sequence whose label matches ignoring case, or -1. */
    public int label(String name) {
        for (int i = 0; i < sequences.size(); i++) if (sequences.get(i).label.equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Every sequence of an activity, by name; empty for none. */
    public int[] activity(String name) {
        if (name == null || name.isEmpty()) return new int[0];
        return java.util.stream.IntStream.range(0, sequences.size()).filter(i -> sequences.get(i).activity.equalsIgnoreCase(name)).toArray();
    }

    /** A bone's model-to-bone transform of the reference pose, row-major 3x4, into {@code out} at {@code at}. */
    public void bind(int bone, float[] out, int at) { System.arraycopy(binds, bone * 12, out, at, 12); }

    /**
     * Every bone's local position and rotation at {@code cycle} of {@code sequence}, as
     * {@code CalcAnimation} interpolates between frames: positions linearly, rotations by
     * {@code QuaternionBlend}. A sequence without frames leaves the reference pose.
     */
    public void localPose(int sequence, float cycle, float[] positions, float[] rotations) {
        Sequence s = sequence >= 0 && sequence < sequences.size() ? sequences.get(sequence) : null;
        if (s == null || s.frames == 0) {
            System.arraycopy(restPositions, 0, positions, 0, restPositions.length);
            System.arraycopy(restRotations, 0, rotations, 0, restRotations.length);
            return;
        }
        float frame = cycle * (s.frames - 1);
        int whole = Math.max(0, Math.min(s.frames - 1, (int) frame));
        float t = Math.max(0, frame - whole);
        int next = Math.min(s.frames - 1, whole + 1);
        for (int bone = 0; bone < parents.length; bone++) {
            float[] p = s.positions[bone], q = s.rotations[bone];
            int a = p.length == 3 ? 0 : whole * 3, b = p.length == 3 ? 0 : next * 3;
            for (int i = 0; i < 3; i++) positions[bone * 3 + i] = p[a + i] + (p[b + i] - p[a + i]) * t;
            int qa = q.length == 4 ? 0 : whole * 4, qb = q.length == 4 ? 0 : next * 4;
            blend(q, qa, q, qb, t, rotations, bone * 4);
        }
    }

    /** {@code QuaternionBlend}: aligned, linear, normalized. */
    public static void blend(float[] p, int pa, float[] q, int qa, float t, float[] out, int at) {
        float a = 0, b = 0;
        for (int i = 0; i < 4; i++) { float d = p[pa + i] - q[qa + i], s = p[pa + i] + q[qa + i]; a += d * d; b += s * s; }
        float sign = a > b ? -1 : 1, length = 0;
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) { r[i] = (1 - t) * p[pa + i] + t * sign * q[qa + i]; length += r[i] * r[i]; }
        length = (float) Math.sqrt(length);
        for (int i = 0; i < 4; i++) out[at + i] = length > 0 ? r[i] / length : r[i];
    }

    /** {@code QuaternionSlerp}, as {@code SlerpBones} blends one sequence into another. */
    public static void slerp(float[] p, int pa, float[] q, int qa, float t, float[] out, int at) {
        float a = 0, b = 0;
        for (int i = 0; i < 4; i++) { float d = p[pa + i] - q[qa + i], s = p[pa + i] + q[qa + i]; a += d * d; b += s * s; }
        float sign = a > b ? -1 : 1;
        float[] q2 = new float[4];
        for (int i = 0; i < 4; i++) q2[i] = sign * q[qa + i];
        float cosom = 0;
        for (int i = 0; i < 4; i++) cosom += p[pa + i] * q2[i];
        if (1 + cosom > 1e-6f) {
            float sclp, sclq;
            if (1 - cosom > 1e-6f) {
                double omega = Math.acos(cosom), sinom = Math.sin(omega);
                sclp = (float) (Math.sin((1 - t) * omega) / sinom); sclq = (float) (Math.sin(t * omega) / sinom);
            } else { sclp = 1 - t; sclq = t; }
            for (int i = 0; i < 4; i++) out[at + i] = sclp * p[pa + i] + sclq * q2[i];
        } else {
            float[] r = {-q2[1], q2[0], -q2[3], q2[2]};
            float sclp = (float) Math.sin((1 - t) * 0.5 * Math.PI), sclq = (float) Math.sin(t * 0.5 * Math.PI);
            for (int i = 0; i < 3; i++) r[i] = sclp * p[pa + i] + sclq * r[i];
            System.arraycopy(r, 0, out, at, 4);
        }
    }

    /**
     * Every bone's skinning transform for a local pose, row-major 3x4 each: bone to model, as
     * {@code BuildBoneChain} composes parents, after the reference pose's model to bone.
     */
    public void skinning(float[] positions, float[] rotations, float[] out) {
        float[] world = new float[parents.length * 12];
        float[] local = new float[12];
        for (int bone = 0; bone < parents.length; bone++) {
            matrix(rotations, bone * 4, positions, bone * 3, local);
            if (parents[bone] < 0) System.arraycopy(local, 0, world, bone * 12, 12);
            else multiply(world, parents[bone] * 12, local, 0, world, bone * 12);
            multiply(world, bone * 12, binds, bone * 12, out, bone * 12);
        }
    }

    /** {@code QuaternionMatrix(q, pos)}, row-major 3x4. */
    public static void matrix(float[] q, int qa, float[] p, int pa, float[] out) {
        float x = q[qa], y = q[qa + 1], z = q[qa + 2], w = q[qa + 3];
        out[0] = 1 - 2 * y * y - 2 * z * z; out[1] = 2 * x * y - 2 * w * z; out[2] = 2 * x * z + 2 * w * y; out[3] = p[pa];
        out[4] = 2 * x * y + 2 * w * z; out[5] = 1 - 2 * x * x - 2 * z * z; out[6] = 2 * y * z - 2 * w * x; out[7] = p[pa + 1];
        out[8] = 2 * x * z - 2 * w * y; out[9] = 2 * y * z + 2 * w * x; out[10] = 1 - 2 * x * x - 2 * y * y; out[11] = p[pa + 2];
    }

    /** {@code ConcatTransforms}: {@code a * b} for row-major 3x4 transforms; {@code out} may not alias {@code b}. */
    public static void multiply(float[] a, int aa, float[] b, int ba, float[] out, int at) {
        float[] r = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                float value = a[aa + row * 4] * b[ba + column] + a[aa + row * 4 + 1] * b[ba + 4 + column] + a[aa + row * 4 + 2] * b[ba + 8 + column];
                if (column == 3) value += a[aa + row * 4 + 3];
                r[row * 4 + column] = value;
            }
        }
        System.arraycopy(r, 0, out, at, 12);
    }

    /** Decodes and checks one payload against the mesh it moves, which has {@code meshVertices} vertices. */
    public static AnimationAsset decode(byte[] bytes, int meshVertices) throws BundleValidationException {
        try {
            return read(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), meshVertices);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException exception) {
            throw new BundleValidationException(BundleErrorCode.INVALID_SCHEMA, "truncated or malformed animation");
        }
    }

    private static AnimationAsset read(ByteBuffer in, int meshVertices) throws BundleValidationException {
        byte[] magic = new byte[8];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) fail("animation magic");
        if (in.getInt() != 1) throw new BundleValidationException(BundleErrorCode.UNSUPPORTED_VERSION, "unsupported animation version");
        int boneCount = in.getInt(), vertexCount = in.getInt(), sequenceCount = in.getInt(), nodes = in.getInt();
        if (boneCount < 1 || boneCount > MAX_BONES) fail("animation bone count");
        if (vertexCount != meshVertices) fail("animation vertex count differs from its mesh");
        if (sequenceCount < 1 || sequenceCount > MAX_SEQUENCES) fail("animation sequence count");
        if (nodes < 0 || nodes > MAX_NODES) fail("animation node count");
        int[] parents = new int[boneCount];
        float[] restPositions = new float[boneCount * 3], restRotations = new float[boneCount * 4], binds = new float[boneCount * 12];
        for (int bone = 0; bone < boneCount; bone++) {
            parents[bone] = in.getInt();
            if (parents[bone] < -1 || parents[bone] >= bone) fail("bone parent must come before it");
            for (int i = 0; i < 3; i++) restPositions[bone * 3 + i] = finite(in.getFloat());
            readRotation(in, restRotations, bone * 4);
            for (int i = 0; i < 12; i++) binds[bone * 12 + i] = finite(in.getFloat());
        }
        byte[] counts = new byte[vertexCount], bones = new byte[vertexCount * 3];
        float[] weights = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int count = in.get() & 0xFF;
            if (count > 3) fail("a vertex has more than three bones");
            counts[vertex] = (byte) count;
            in.get(bones, vertex * 3, 3);
            for (int slot = 0; slot < 3; slot++) {
                float weight = finite(in.getFloat());
                weights[vertex * 3 + slot] = weight;
                boolean used = slot < count;
                if (used ? weight <= 0 || (bones[vertex * 3 + slot] & 0xFF) >= boneCount : weight != 0 || bones[vertex * 3 + slot] != 0)
                    fail("invalid vertex bone binding");
            }
        }
        byte[] transitions = new byte[nodes * nodes];
        in.get(transitions);
        List<Sequence> sequences = new ArrayList<>(sequenceCount);
        for (int s = 0; s < sequenceCount; s++) {
            String label = name(in), activity = name(in);
            int activityWeight = in.getInt(), flags = in.getInt();
            float fadeIn = finite(in.getFloat()), fadeOut = finite(in.getFloat());
            int entry = in.getInt(), exit = in.getInt(), nodeFlags = in.getInt();
            float cps = finite(in.getFloat());
            if (entry < 0 || exit < 0 || entry > nodes || exit > nodes) fail("sequence transition node out of range");
            float[] boneWeights = new float[boneCount];
            for (int bone = 0; bone < boneCount; bone++) boneWeights[bone] = finite(in.getFloat());
            int frames = in.getInt();
            if (frames < 0 || frames > MAX_FRAMES) fail("sequence frame count");
            float[][] positions = new float[frames == 0 ? 0 : boneCount][], rotations = new float[frames == 0 ? 0 : boneCount][];
            for (int bone = 0; frames > 0 && bone < boneCount; bone++) {
                int positionTrack = in.get(), rotationTrack = in.get();
                if ((positionTrack & ~1) != 0 || (rotationTrack & ~1) != 0) fail("invalid track kind");
                int pn = positionTrack == 1 ? frames : 1, rn = rotationTrack == 1 ? frames : 1;
                if (in.remaining() < pn * 12L + rn * 8L) fail("truncated animation track");
                positions[bone] = new float[pn * 3];
                for (int i = 0; i < pn * 3; i++) positions[bone][i] = finite(in.getFloat());
                rotations[bone] = new float[rn * 4];
                for (int i = 0; i < rn; i++) readRotation(in, rotations[bone], i * 4);
            }
            sequences.add(new Sequence(label, activity, activityWeight, flags, fadeIn, fadeOut, entry, exit, nodeFlags, cps, boneWeights,
                frames, positions, rotations));
        }
        if (in.hasRemaining()) fail("trailing animation bytes");
        return new AnimationAsset(parents, restPositions, restRotations, binds, counts, bones, weights, nodes, transitions, sequences);
    }

    private static void readRotation(ByteBuffer in, float[] out, int at) throws BundleValidationException {
        float length = 0;
        for (int i = 0; i < 4; i++) { out[at + i] = in.getShort() / 32767f; length += out[at + i] * out[at + i]; }
        if (length < 0.25f) fail("rotation is not a unit quaternion");
        length = (float) Math.sqrt(length);
        for (int i = 0; i < 4; i++) out[at + i] /= length;
    }

    private static String name(ByteBuffer in) throws BundleValidationException {
        int length = in.getShort() & 0xFFFF;
        if (length > MAX_NAME) fail("sequence name too long");
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static float finite(float value) throws BundleValidationException {
        if (!Float.isFinite(value)) fail("animation value is not finite");
        return value;
    }

    private static void fail(String message) throws BundleValidationException {
        throw new BundleValidationException(BundleErrorCode.INVALID_SCHEMA, message);
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "AnimationAsset[%d bones, %d sequences]", parents.length, sequences.size());
    }
}
