package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class BundleValidatorTest {
    @TempDir
    Path directory;

    @Test
    void validatesExternalConverterBundleWhenRequested() throws Exception {
        String path = System.getProperty("src2mc.testBundle");
        Assumptions.assumeTrue(path != null, "set -Dsrc2mc.testBundle to exercise converter/mod compatibility");
        BundleManifest manifest = new BundleValidator().validate(Path.of(path));
        org.junit.jupiter.api.Assertions.assertFalse(manifest.maps().isEmpty());
        org.junit.jupiter.api.Assertions.assertNotNull(manifest.maps().getFirst().atlas());
        for (BundleMap map : manifest.maps()) {
            if (map.logic() == null) continue;
            System.out.println("validated " + map.mapId() + ": " + map.logic().entities().size() + " entities, "
                + map.logic().volumes().size() + " volumes, " + map.logic().scenes().size() + " scenes, "
                + map.logic().captions().size() + " captions, " + (map.audio() == null ? 0 : map.audio().scripts().size()) + " scripts");
        }
    }

    @Test
    void validatesCanonicalManifestAndPayloadHash() throws Exception {
        Path bundle = directory.resolve("valid.src2mc");
        writeBundle(bundle, 1, "hl2", "campaign.json", campaign("hl2"), false);

        BundleManifest manifest = new BundleValidator().validate(bundle);

        assertEquals("hl2", manifest.campaignId());
        assertEquals(1, manifest.entries().size());
        assertEquals(campaign("hl2").length, manifest.uncompressedBytes());
    }

    @Test
    void rejectsCorruptionUnsupportedVersionsAndUnsafePaths() throws Exception {
        Path corrupt = directory.resolve("corrupt.src2mc");
        writeBundle(corrupt, 1, "hl2", "campaign.json", "bad\n".getBytes(StandardCharsets.UTF_8), true);
        assertCode(BundleErrorCode.HASH_MISMATCH, corrupt);

        Path unsupported = directory.resolve("unsupported.src2mc");
        writeBundle(unsupported, 2, "hl2", "campaign.json", campaign("hl2"), false);
        assertCode(BundleErrorCode.UNSUPPORTED_VERSION, unsupported);

        Path unsafe = directory.resolve("unsafe.src2mc");
        writeBundle(unsafe, 1, "hl2", "../campaign.json", "{}\n".getBytes(StandardCharsets.UTF_8), false);
        assertCode(BundleErrorCode.UNSAFE_PATH, unsafe);
    }

    @Test
    void failedReloadRetainsLastKnownGoodGeneration() throws Exception {
        Path bundle = directory.resolve("campaign.src2mc");
        writeBundle(bundle, 1, "hl2", "campaign.json", campaign("hl2"), false);
        var repository = new BundleRepository(() -> directory);
        BundleGeneration good = repository.reload();

        writeBundle(bundle, 1, "hl2", "campaign.json", "corrupt\n".getBytes(StandardCharsets.UTF_8), true);
        assertThrows(BundleValidationException.class, repository::reload);

        assertEquals(good, repository.active());
        assertEquals(1, repository.active().sequence());
    }

    @Test
    void loadsSeveralBundlesInParallelWithAStableResult() throws Exception {
        for (int i = 0; i < 6; i++) {
            String campaign = "hl" + i;
            writeBundle(directory.resolve(campaign + ".src2mc"), 1, campaign, "campaign.json", campaign(campaign), false);
        }
        var repository = new BundleRepository(() -> directory);

        BundleGeneration first = repository.reload();
        BundleGeneration second = repository.reload();

        assertEquals(6, first.bundles().size());
        assertEquals(first.fingerprint(), second.fingerprint());
        // Filename order, whatever order the threads finished in.
        assertEquals(List.of("hl0", "hl1", "hl2", "hl3", "hl4", "hl5"),
            second.bundles().stream().map(BundleManifest::campaignId).toList());
    }

    @Test
    void reloadAsyncPublishesTheSameGenerationAndReportsProgress() throws Exception {
        writeBundle(directory.resolve("campaign.src2mc"), 1, "hl2", "campaign.json", campaign("hl2"), false);
        var repository = new BundleRepository(() -> directory);

        BundleGeneration published = repository.reloadAsync().get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(published, repository.active());
        assertEquals(BundleLoadProgress.State.DONE, BundleLoadProgress.snapshot().state());
        assertEquals(1, BundleLoadProgress.snapshot().total());
    }

    @Test
    void aFailedAsyncLoadIsReportedWithoutReplacingTheActiveGeneration() throws Exception {
        Path bundle = directory.resolve("campaign.src2mc");
        writeBundle(bundle, 1, "hl2", "campaign.json", campaign("hl2"), false);
        var repository = new BundleRepository(() -> directory);
        BundleGeneration good = repository.reload();

        writeBundle(bundle, 1, "hl2", "campaign.json", "corrupt\n".getBytes(StandardCharsets.UTF_8), true);
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> repository.reloadAsync().get(30, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(good, repository.active());
        assertEquals(BundleLoadProgress.State.FAILED, BundleLoadProgress.snapshot().state());
    }

    @Test
    void validatesCompleteMapSchemasAndRejectsMalformedBinary() throws Exception {
        Path valid = directory.resolve("map.src2mc");
        Map<String, byte[]> payloads = emptyMapPayloads();
        writeBundle(valid, 1, "hl2", payloads);
        assertEquals(5, new BundleValidator().validate(valid).entries().size());

        Path malformed = directory.resolve("bad-face.src2mc");
        payloads = new TreeMap<>(payloads);
        payloads.put("maps/d1_01/surfaces.s2faces", new byte[24]);
        writeBundle(malformed, 1, "hl2", payloads);
        assertCode(BundleErrorCode.INVALID_SCHEMA, malformed);
    }

    /** Flag bytes: owned bit plus each owner-offset axis stored as offset + 1. */
    private static final int UNOWNED = 0x2A, OWNED = 0x2B;
    private static int ownedAt(int dx, int dy, int dz) { return 1 | (dx + 1) << 1 | (dy + 1) << 3 | (dz + 1) << 5; }

    @Test
    void loadsExactSurfaceFragments() throws Exception {
        Path bundle = directory.resolve("fragments.src2mc");
        int cell = (2 << 8) | (3 << 4) | 4;
        writeBundle(bundle, 1, "hl2", fragmentPayloads(surfaces(
            fragment(cell, OWNED, 0, 0, 0, 7, 1, 0, 0, 0, 4096, 0, 0, 4096, 2048, 0),
            fragment(cell, ownedAt(1, -1, 0), 1, 0, 0, 4, 0, 0, 1024, 0, 4096, 1024, 0, 4096, 1024, 4096),
            fragment(cell, UNOWNED, 2, 0, 0, 3, 0, 0, 1024, 4096, 4096, 1024, 4096, 4096, 1024, 0))));

        SurfaceTable table = new BundleValidator().validate(bundle).maps().getFirst().surfaces();

        List<SurfaceTable.Face> faces = table.facesAt(4, 2, 3);
        assertEquals(3, faces.size());
        assertEquals(true, faces.get(0).owned());
        assertEquals(List.of(0, 0, 0), List.of(faces.get(0).ownerDx(), faces.get(0).ownerDy(), faces.get(0).ownerDz()));
        assertEquals(0, faces.get(0).provenance());
        assertEquals(7, faces.get(0).sourcePrimary());
        assertEquals(3, faces.get(0).vertexCount());
        assertEquals(0.5, faces.get(0).coordinate(2, 1));
        // Owned by the block one east and one down; still bucketed and positioned in its own cell.
        assertEquals(List.of(1, -1, 0), List.of(faces.get(1).ownerDx(), faces.get(1).ownerDy(), faces.get(1).ownerDz()));
        assertEquals(cell, faces.get(1).localCell());
        assertEquals(false, faces.get(2).owned());
        assertEquals(2, faces.get(2).provenance());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[]{0, 1, 0}, faces.get(2).normal(), 1e-12);
    }

    /** {@code double_sided} is Source's $nocull; written only when set, so false is not canonical. */
    @Test
    void readsDoubleSidedMaterialsAndRejectsAWrittenFalse() throws Exception {
        byte[] surfaces = surfaces(fragment((2 << 8) | (3 << 4) | 4, OWNED, 0, 0, 0, 7, 1, 0, 0, 0, 4096, 0, 0, 4096, 2048, 0));
        for (String flag : List.of("true", "false")) {
            Map<String, byte[]> payloads = fragmentPayloads(surfaces);
            payloads.put("maps/d1_01.json", new String(payloads.get("maps/d1_01.json"), StandardCharsets.UTF_8)
                .replace("\"reflectivity\":[0.5,0.5,0.5]}", "\"reflectivity\":[0.5,0.5,0.5],\"double_sided\":" + flag + "}")
                .getBytes(StandardCharsets.UTF_8));
            Path bundle = directory.resolve("double-sided-" + flag + ".src2mc");
            writeBundle(bundle, 1, "hl2", payloads);
            if (flag.equals("true")) {
                org.junit.jupiter.api.Assertions.assertTrue(
                    new BundleValidator().validate(bundle).maps().getFirst().materials().getFirst().doubleSided());
            } else {
                assertCode(BundleErrorCode.INVALID_SCHEMA, bundle);
            }
        }
    }

    @Test
    void rejectsMalformedSurfaceFragments() throws Exception {
        int[] triangle = {0, 0, 0, 4096, 0, 0, 4096, 4096, 0};
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "bit 7", fragment(0, OWNED | 0x80, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "x offset 3", fragment(0, OWNED | 0b110, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "y offset 3", fragment(0, OWNED | 0b11000, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "z offset 3", fragment(0, OWNED | 0b1100000, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "unowned with offset", fragment(0, ownedAt(0, -1, 0) & ~1, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "unowned all-zero byte", fragment(0, 0, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "kind", fragment(0, UNOWNED, 3, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "cell", fragment(0x1000, UNOWNED, 0, 0, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "two vertices", fragment(0, UNOWNED, 0, 0, 0, 0, 0, 0, 0, 0, 4096, 0, 0));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "65 vertices", fragment(0, UNOWNED, 0, 0, 0, 0, 0, new int[65 * 3]));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "coordinate",
            fragment(0, UNOWNED, 0, 0, 0, 0, 0, 0, 0, 0, 4097, 0, 0, 4096, 4096, 0));
        assertFragmentsRejected(BundleErrorCode.INVALID_REFERENCE, "material", fragment(0, UNOWNED, 0, 1, 0, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_REFERENCE, "uv", fragment(0, UNOWNED, 0, 0, 1, 0, 0, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "duplicate key",
            fragment(0, UNOWNED, 0, 0, 0, 5, 1, triangle), fragment(0, OWNED, 0, 0, 0, 5, 1, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "secondary order",
            fragment(0, UNOWNED, 0, 0, 0, 5, 2, triangle), fragment(0, UNOWNED, 0, 0, 0, 5, 1, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "kind order",
            fragment(0, UNOWNED, 2, 0, 0, 0, 0, triangle), fragment(0, UNOWNED, 1, 0, 0, 9, 9, triangle));
        assertFragmentsRejected(BundleErrorCode.INVALID_SCHEMA, "cell order",
            fragment(1, UNOWNED, 0, 0, 0, 0, 0, triangle), fragment(0, UNOWNED, 0, 0, 0, 0, 0, triangle));

        byte[] truncated = surfaces(fragment(0, UNOWNED, 0, 0, 0, 0, 0, triangle));
        truncated = java.util.Arrays.copyOf(truncated, truncated.length - 2);
        assertSurfacesRejected(BundleErrorCode.INVALID_SCHEMA, "truncated", truncated);

        byte[] version1 = surfaces(fragment(0, UNOWNED, 0, 0, 0, 0, 0, triangle));
        version1[8] = 1;
        assertSurfacesRejected(BundleErrorCode.UNSUPPORTED_VERSION, "version 1", version1);

        byte[] overCounted = surfaces(fragment(0, UNOWNED, 0, 0, 0, 0, 0, triangle));
        overCounted[20] = 2; // header fragment count 2, one record written
        assertSurfacesRejected(BundleErrorCode.INVALID_SCHEMA, "fragment count", overCounted);
    }

    private void assertFragmentsRejected(BundleErrorCode code, String label, byte[]... fragments) throws Exception {
        assertSurfacesRejected(code, label, surfaces(fragments));
    }

    private void assertSurfacesRejected(BundleErrorCode code, String label, byte[] surfaces) throws Exception {
        Path bundle = directory.resolve("bad-fragment.src2mc");
        writeBundle(bundle, 1, "hl2", fragmentPayloads(surfaces));
        BundleValidationException exception = assertThrows(BundleValidationException.class,
            () -> new BundleValidator().validate(bundle), label);
        assertEquals(code, exception.code(), label + ": " + exception.getMessage());
    }

    private static Map<String, byte[]> fragmentPayloads(byte[] surfaces) {
        Map<String, byte[]> payloads = emptyMapPayloads();
        payloads.put("maps/d1_01.json", new String(payloads.get("maps/d1_01.json"), StandardCharsets.UTF_8)
            .replace("\"materials\":[]", "\"materials\":[{\"source_material\":\"tools/nodraw\",\"render_class\":\"fallback\","
                + "\"reflectivity\":[0.5,0.5,0.5]}]").getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01/surfaces.s2faces", surfaces);
        return payloads;
    }

    /** A v2 surface table: one UV region and every fragment in section (0, 0, 0). */
    private static byte[] surfaces(byte[]... fragments) {
        int size = 8 + 16 + 64 + 16;
        for (byte[] fragment : fragments) size += fragment.length;
        ByteBuffer output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        output.put("S2FACE\0\0".getBytes(StandardCharsets.ISO_8859_1));
        output.putInt(2).putInt(1).putInt(1).putInt(fragments.length);
        for (double value : new double[]{1, 0, 0, 0, 0, 1, 0, 0}) output.putDouble(value);
        output.putInt(0).putInt(0).putInt(0).putInt(fragments.length);
        for (byte[] fragment : fragments) output.put(fragment);
        return output.array();
    }

    private static byte[] fragment(int cell, int flags, int kind, int material, int uv, int primary, int secondary, int... coords) {
        ByteBuffer output = ByteBuffer.allocate(21 + coords.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        output.putShort((short) cell).put((byte) flags).put((byte) kind)
            .putInt(material).putInt(uv).putInt(primary).putInt(secondary).put((byte) (coords.length / 3));
        for (int coord : coords) output.putShort((short) coord);
        return output.array();
    }

    private void assertCode(BundleErrorCode code, Path bundle) {
        BundleValidationException exception = assertThrows(
            BundleValidationException.class,
            () -> new BundleValidator().validate(bundle)
        );
        assertEquals(code, exception.code());
    }

    private static void writeBundle(
        Path output,
        int version,
        String campaign,
        String payloadPath,
        byte[] payload,
        boolean lieAboutPayload
    ) throws Exception {
        byte[] declared = lieAboutPayload ? "{}\n".getBytes(StandardCharsets.UTF_8) : payload;
        String payloadHash = sha256(declared);
        var entry = new BundleManifest.Entry(payloadPath, declared.length, payloadHash);
        String fingerprint = fingerprint(List.of(entry));
        String manifest = "{\"format\":\"src2mc-campaign\",\"version\":" + version
            + ",\"campaign_id\":\"" + campaign + "\",\"fingerprint\":\"" + fingerprint
            + "\",\"entries\":[{\"path\":\"" + payloadPath + "\",\"size\":" + declared.length
            + ",\"sha256\":\"" + payloadHash + "\"}]}\n";
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(output))) {
            put(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(zip, payloadPath, payload);
        }
    }

    private static void writeBundle(Path output, int version, String campaign, Map<String, byte[]> payloads) throws Exception {
        List<BundleManifest.Entry> declared = payloads.entrySet().stream().map(entry -> {
            try {
                return new BundleManifest.Entry(entry.getKey(), entry.getValue().length, sha256(entry.getValue()));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).toList();
        StringBuilder manifest = new StringBuilder("{\"format\":\"src2mc-campaign\",\"version\":")
            .append(version).append(",\"campaign_id\":\"").append(campaign)
            .append("\",\"fingerprint\":\"").append(fingerprint(declared)).append("\",\"entries\":[");
        for (int i = 0; i < declared.size(); i++) {
            BundleManifest.Entry entry = declared.get(i);
            if (i != 0) manifest.append(',');
            manifest.append("{\"path\":\"").append(entry.path()).append("\",\"size\":")
                .append(entry.size()).append(",\"sha256\":\"").append(entry.sha256()).append("\"}");
        }
        manifest.append("]}\n");
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(output))) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : payloads.entrySet()) put(zip, entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, byte[]> emptyMapPayloads() {
        Map<String, byte[]> payloads = new TreeMap<>();
        payloads.put("campaign.json", ("{\"format\":\"src2mc-campaign-metadata\",\"version\":1,\"campaign_id\":\"hl2\","
            + "\"maps\":[{\"map_id\":\"d1_01\",\"metadata\":\"maps/d1_01.json\"}]}\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01.json", ("{\"format\":\"src2mc-map\",\"version\":1,\"map_id\":\"d1_01\",\"source_name\":\"d1_01\","
            + "\"units_per_block\":32.0,\"cell_min\":[0,0,0],\"cell_max\":[0,0,0],\"anchor_cell\":[0,0,0],"
            + "\"surfaces\":\"maps/d1_01/surfaces.s2faces\",\"materials\":[],\"models\":[],"
            + "\"props\":\"maps/d1_01/props.s2props\",\"diagnostics\":\"maps/d1_01/diagnostics.json\"}\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01/diagnostics.json", "{\"format\":\"src2mc-diagnostics\",\"version\":1,\"messages\":[]}\n".getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01/props.s2props", binaryHeader("S2PROP\0\0", 1, 0));
        payloads.put("maps/d1_01/surfaces.s2faces", binaryHeader("S2FACE\0\0", 2, 0, 0, 0));
        return payloads;
    }

    private static byte[] binaryHeader(String magic, int... values) {
        ByteBuffer output = ByteBuffer.allocate(8 + values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        output.put(magic.getBytes(StandardCharsets.ISO_8859_1));
        for (int value : values) output.putInt(value);
        return output.array();
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] campaign(String id) {
        return ("{\"format\":\"src2mc-campaign-metadata\",\"version\":1,\"campaign_id\":\""
            + id + "\",\"maps\":[]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String fingerprint(List<BundleManifest.Entry> entries) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("src2mc-manifest-v1\0".getBytes(StandardCharsets.US_ASCII));
        for (BundleManifest.Entry entry : entries) {
            byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(entry.size()).array());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
