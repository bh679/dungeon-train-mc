package games.brennan.dungeontrain.client.version.compare;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns each platform's listing JSON into a {@link PlatformVersions}. Pure — no network, no
 * Minecraft — so the two response shapes are pinned by unit tests rather than discovered in-game.
 *
 * <ul>
 *   <li><b>Modrinth</b> — {@code GET /v2/project/<id>/version} returns an array of versions, each
 *       with {@code version_number} ("0.849.0", older uploads "v0.849.0"), {@code changelog}
 *       (curated markdown) and {@code date_published}.</li>
 *   <li><b>CurseForge</b> — the keyless {@code api.cfwidget.com/<id>} mirror returns
 *       {@code files[]}, each with {@code name} ("dungeon-train-0.828.0.zip") and
 *       {@code uploaded_at}. No changelog.</li>
 * </ul>
 *
 * <p>Entries whose version is not a strict {@code X.Y.Z} are dropped rather than failing the whole
 * listing: one oddly named upload must not blank the page.</p>
 */
public final class VersionCatalogParser {

    private static final Pattern CF_FILE_VERSION = Pattern.compile("dungeon-train-(\\d+\\.\\d+\\.\\d+)\\.zip$");

    private VersionCatalogParser() {}

    /** @throws IllegalArgumentException when the body is not the expected shape */
    public static PlatformVersions parseModrinth(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Modrinth listing: expected a JSON array");
        }
        List<ReleaseEntry> entries = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Optional<FullSemver> version = FullSemver.parse(string(o, "version_number"));
            if (version.isEmpty()) continue;
            entries.add(new ReleaseEntry(version.get(), string(o, "changelog"), orEmpty(string(o, "date_published"))));
        }
        return new PlatformVersions(Platform.MODRINTH, entries);
    }

    /** @throws IllegalArgumentException when the body is not the expected shape */
    public static PlatformVersions parseCurseForge(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("files")) {
            throw new IllegalArgumentException("CurseForge listing: expected an object with files[]");
        }
        JsonElement files = root.getAsJsonObject().get("files");
        if (!files.isJsonArray()) {
            throw new IllegalArgumentException("CurseForge listing: files is not an array");
        }
        List<ReleaseEntry> entries = new ArrayList<>();
        for (JsonElement el : (JsonArray) files) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = string(o, "name");
            if (name == null) continue;
            Matcher m = CF_FILE_VERSION.matcher(name);
            if (!m.find()) continue;
            Optional<FullSemver> version = FullSemver.parse(m.group(1));
            if (version.isEmpty()) continue;
            entries.add(new ReleaseEntry(version.get(), null, orEmpty(string(o, "uploaded_at"))));
        }
        return new PlatformVersions(Platform.CURSEFORGE, entries);
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
