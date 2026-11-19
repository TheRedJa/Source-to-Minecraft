package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.io.TempDir;

/** The logic table's {@code titles} and {@code credits} (format.md section 16) through the bundle validator. */
final class LogicTitlesTest {
    @TempDir
    Path directory;

    // The tail the converter's logic.rs test titles_and_credits_serialize_in_the_documented_order writes.
    private static final String TITLES = "\"titles\":[{\"name\":\"CHAPTER1_TITLE\",\"x\":-1.0,\"y\":0.58,\"effect\":2,"
        + "\"color1\":[100,100,100,255],\"color2\":[240,110,0,255],\"fadein\":0.01,\"fadeout\":1.5,\"holdtime\":3.5,"
        + "\"fxtime\":0.25,\"text\":\"POINT INSERTION\"}]";
    private static final String CREDITS = "\"credits\":{\"params\":{\"logo\":\"HALF-LIFE'\"},\"intro\":[{\"name\":\"V\","
        + "\"font\":\"WeaponIcons\"},{\"name\":\" \",\"font\":\"CreditsText\"},{\"name\":\" \",\"font\":\"CreditsText\"}],"
        + "\"outro\":[],\"logo_image\":\"logic/credits_logo.png\"}";
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};

    @Test
    void readsTitlesAndCredits() throws Exception {
        Path bundle = directory.resolve("titles.src2mc");
        writeBundle(bundle, logic(TITLES + "," + CREDITS), true);
        LogicTable table = new BundleValidator().validate(bundle).maps().getFirst().logic();
        LogicTable.Title title = table.title("#chapter1_title");
        assertNotNull(title);
        assertEquals("CHAPTER1_TITLE", title.name());
        assertEquals(-1f, title.x());
        assertEquals(0.58f, title.y());
        assertEquals(2, title.effect());
        assertEquals(0xFF646464, title.color1());
        assertEquals(0xFFF06E00, title.color2());
        assertEquals(0.01f, title.fadein());
        assertEquals(1.5f, title.fadeout());
        assertEquals(3.5f, title.holdtime());
        assertEquals(0.25f, title.fxtime());
        assertEquals("POINT INSERTION", title.text());
        assertNull(table.title("missing"));
        LogicTable.Credits credits = table.credits();
        assertEquals("HALF-LIFE'", credits.params().get("logo"));
        assertEquals(List.of(new LogicTable.CreditName("V", "WeaponIcons"), new LogicTable.CreditName(" ", "CreditsText"),
            new LogicTable.CreditName(" ", "CreditsText")), credits.intro());
        assertEquals("maps/d1_01/logic/credits_logo.png", credits.logoImage());
    }

    @Test
    void anAbsentTitleListAndCreditsReadAsEmpty() throws Exception {
        Path bundle = directory.resolve("bare.src2mc");
        writeBundle(bundle, logic(null), false);
        LogicTable table = new BundleValidator().validate(bundle).maps().getFirst().logic();
        assertEquals(List.of(), table.titles());
        assertNull(table.credits());
    }

    @Test
    void rejectsMalformedTitlesAndAMissingLogo() throws Exception {
        Path badColor = directory.resolve("color.src2mc");
        writeBundle(badColor, logic(TITLES.replace("[240,110,0,255]", "[240,110,0,256]")), false);
        assertCode(BundleErrorCode.INVALID_SCHEMA, badColor);
        Path duplicate = directory.resolve("duplicate.src2mc");
        String twice = TITLES.substring(0, TITLES.length() - 1) + "," + TITLES.substring(TITLES.indexOf('{'));
        writeBundle(duplicate, logic(twice.replace("CHAPTER1_TITLE\"", "chapter1_title\"").replaceFirst("chapter1_title", "CHAPTER1_TITLE")), false);
        assertCode(BundleErrorCode.DUPLICATE_IDENTITY, duplicate);
        Path noLogo = directory.resolve("nologo.src2mc");
        writeBundle(noLogo, logic(CREDITS), false);
        assertCode(BundleErrorCode.MISSING_ENTRY, noLogo);
        Path elsewhere = directory.resolve("elsewhere.src2mc");
        writeBundle(elsewhere, logic(CREDITS.replace("logic/credits_logo.png", "logic/other.png")), true);
        assertCode(BundleErrorCode.INVALID_REFERENCE, elsewhere);
    }

    private void assertCode(BundleErrorCode code, Path bundle) {
        BundleValidationException exception = assertThrows(BundleValidationException.class, () -> new BundleValidator().validate(bundle));
        assertEquals(code, exception.code());
    }

    private static byte[] logic(String tail) {
        return ("{\"format\":\"src2mc-logic\",\"version\":1,\"source_origin\":[0.0,0.0,0.0],\"entities\":[],\"volumes\":[],"
            + "\"scenes\":[],\"captions\":[]" + (tail == null ? "" : "," + tail) + "}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeBundle(Path output, byte[] logic, boolean withLogo) throws Exception {
        Map<String, byte[]> payloads = new TreeMap<>();
        payloads.put("campaign.json", ("{\"format\":\"src2mc-campaign-metadata\",\"version\":1,\"campaign_id\":\"hl2\","
            + "\"maps\":[{\"map_id\":\"d1_01\",\"metadata\":\"maps/d1_01.json\"}]}\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01.json", ("{\"format\":\"src2mc-map\",\"version\":2,\"map_id\":\"d1_01\",\"source_name\":\"d1_01\","
            + "\"units_per_block\":32.0,\"cell_min\":[0,0,0],\"cell_max\":[0,0,0],\"anchor_cell\":[0,0,0],"
            + "\"surfaces\":\"maps/d1_01/surfaces.s2faces\",\"materials\":[],\"models\":[],"
            + "\"props\":\"maps/d1_01/props.s2props\",\"logic\":\"maps/d1_01/logic.json\","
            + "\"diagnostics\":\"maps/d1_01/diagnostics.json\"}\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01/diagnostics.json", "{\"format\":\"src2mc-diagnostics\",\"version\":1,\"messages\":[]}\n".getBytes(StandardCharsets.UTF_8));
        payloads.put("maps/d1_01/props.s2props", header("S2PROP\0\0", 1, 0));
        payloads.put("maps/d1_01/surfaces.s2faces", header("S2FACE\0\0", 5, 0, 0, 0, 0, 0));
        payloads.put("maps/d1_01/logic.json", logic);
        if (withLogo) payloads.put("maps/d1_01/logic/credits_logo.png", PNG);
        List<BundleManifest.Entry> declared = payloads.entrySet().stream()
            .map(e -> new BundleManifest.Entry(e.getKey(), e.getValue().length, sha256(e.getValue()))).toList();
        StringBuilder manifest = new StringBuilder("{\"format\":\"src2mc-campaign\",\"version\":1,\"campaign_id\":\"hl2\",\"fingerprint\":\"")
            .append(fingerprint(declared)).append("\",\"entries\":[");
        for (int i = 0; i < declared.size(); i++) {
            BundleManifest.Entry entry = declared.get(i);
            if (i != 0) manifest.append(',');
            manifest.append("{\"path\":\"").append(entry.path()).append("\",\"size\":").append(entry.size())
                .append(",\"sha256\":\"").append(entry.sha256()).append("\"}");
        }
        manifest.append("]}\n");
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(output))) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : payloads.entrySet()) put(zip, entry.getKey(), entry.getValue());
        }
    }

    private static byte[] header(String magic, int... values) {
        ByteBuffer output = ByteBuffer.allocate(8 + values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        output.put(magic.getBytes(StandardCharsets.ISO_8859_1));
        for (int value : values) output.putInt(value);
        return output.array();
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String fingerprint(List<BundleManifest.Entry> entries) {
        MessageDigest digest = digest();
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

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
