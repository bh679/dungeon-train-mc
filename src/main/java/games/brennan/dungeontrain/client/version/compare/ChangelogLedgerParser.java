package games.brennan.dungeontrain.client.version.compare;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns {@code changelog.json} into a {@link ChangelogLedger}. Pure — no network, no Minecraft.
 * Only entries that have shipped ({@code released: true} with a parseable {@code released_in}) are
 * kept: an unreleased entry describes a build no player can have. Tag ids the client does not know
 * are ignored so a ledger written after a taxonomy change still loads; an entry that is not the
 * expected shape is skipped, never fatal — one bad row must not blank the page's filters.
 */
public final class ChangelogLedgerParser {

    private ChangelogLedgerParser() {}

    /** @throws IllegalArgumentException when the body is not an object with an {@code entries} array */
    public static ChangelogLedger parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")) {
            throw new IllegalArgumentException("Changelog ledger: expected an object with entries[]");
        }
        JsonElement entries = root.getAsJsonObject().get("entries");
        if (!entries.isJsonArray()) {
            throw new IllegalArgumentException("Changelog ledger: entries is not an array");
        }
        List<LedgerEntry> out = new ArrayList<>();
        for (JsonElement el : entries.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            parseEntry(el.getAsJsonObject()).ifPresent(out::add);
        }
        return new ChangelogLedger(out);
    }

    static Optional<LedgerEntry> parseEntry(JsonObject o) {
        JsonElement released = o.get("released");
        if (released == null || !released.isJsonPrimitive() || !released.getAsBoolean()) {
            return Optional.empty();
        }
        Optional<FullSemver> releasedIn = FullSemver.parse(string(o, "released_in"));
        Optional<FullSemver> version = FullSemver.parse(string(o, "version"));
        String id = string(o, "id");
        if (releasedIn.isEmpty() || version.isEmpty() || id == null) {
            return Optional.empty();
        }
        return Optional.of(new LedgerEntry(
                id,
                version.get(),
                orEmpty(string(o, "type")),
                orEmpty(string(o, "title")),
                orEmpty(string(o, "summary")),
                strings(o.get("highlights")),
                tags(o.get("tags")),
                releasedIn.get()));
    }

    private static Set<ChangelogTag> tags(JsonElement el) {
        Set<ChangelogTag> out = EnumSet.noneOf(ChangelogTag.class);
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement t : (JsonArray) el) {
            if (t.isJsonPrimitive()) {
                ChangelogTag.fromJson(t.getAsString()).ifPresent(out::add);
            }
        }
        return out;
    }

    private static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement s : (JsonArray) el) {
            if (s.isJsonPrimitive()) {
                String text = s.getAsString();
                if (!text.isBlank()) out.add(text);
            }
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
