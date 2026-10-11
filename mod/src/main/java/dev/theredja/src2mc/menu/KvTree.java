package dev.theredja.src2mc.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Source KeyValues block as format section 26 writes it: an ordered list of keys, each a text or a nested
 * block, duplicates kept. Keys match ignoring case, as {@code KeyValues} does.
 */
public record KvTree(List<Entry> entries) {
    /** {@code value} is set for a leaf, {@code block} for a nested block. */
    public record Entry(String key, String value, KvTree block) {
        public boolean isBlock() { return block != null; }
    }

    public static final KvTree EMPTY = new KvTree(List.of());

    public KvTree { entries = List.copyOf(entries); }

    /** Reads {@code [[key, value-or-array], ...]}; malformed pairs are skipped. */
    public static KvTree of(JsonElement element) {
        if (element == null || !element.isJsonArray()) return EMPTY;
        List<Entry> entries = new ArrayList<>();
        for (JsonElement pair : (JsonArray) element) {
            if (!pair.isJsonArray() || pair.getAsJsonArray().size() != 2 || !pair.getAsJsonArray().get(0).isJsonPrimitive()) continue;
            String key = pair.getAsJsonArray().get(0).getAsString();
            JsonElement value = pair.getAsJsonArray().get(1);
            if (value.isJsonArray()) entries.add(new Entry(key, null, of(value)));
            else if (value.isJsonPrimitive()) entries.add(new Entry(key, value.getAsString(), null));
        }
        return new KvTree(entries);
    }

    /** The first leaf called {@code key}, or null. */
    public String get(String key) {
        for (Entry entry : entries) if (!entry.isBlock() && entry.key().equalsIgnoreCase(key)) return entry.value();
        return null;
    }

    public String get(String key, String fallback) {
        String value = get(key);
        return value == null ? fallback : value;
    }

    /** The first nested block called {@code key}, or null. */
    public KvTree block(String key) {
        for (Entry entry : entries) if (entry.isBlock() && entry.key().equalsIgnoreCase(key)) return entry.block();
        return null;
    }

    public boolean has(String key) {
        for (Entry entry : entries) if (entry.key().equalsIgnoreCase(key)) return true;
        return false;
    }

    /** {@code key} in lower case, for map keys. */
    public static String lower(String text) { return text.toLowerCase(Locale.ROOT); }
}
