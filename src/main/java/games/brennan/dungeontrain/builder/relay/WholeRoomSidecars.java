package games.brennan.dungeontrain.builder.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.editor.WholeVariantBlocks;
import games.brennan.dungeontrain.train.WholeKind;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The sidecar half of a "Whole carriage room" relay download — {@link TemplateSidecars}' opposite number
 * for a destination that is not a {@link BuilderPhotoPaths.Kind}.
 *
 * <h2>Why this is its own class</h2>
 * Every other install destination is one of the six {@code Kind}s, so {@code TemplateSidecars.filesFor}
 * and {@code plotKeyFor} can answer for it from an exhaustive switch. A whole room is not a kind: it is a
 * place a CARRIAGE or CONTENTS build can be <em>sent</em> ({@link BuilderRelayWholeRoom#supports}). An
 * incoming document therefore always speaks in the source kind's terms — its roles, its plot key, its
 * coordinate frame — and has to be translated rather than written.
 *
 * <h2>What travels</h2>
 * Two roles of the four a carriage build can carry:
 * <ul>
 *   <li>{@code variants} → {@code <WholeKind.userSubdir()>/<id>.variants.json}, read by
 *       {@link WholeVariantBlocks} and rolled by {@code WholeOverlay} at stamp time.</li>
 *   <li>{@code containers} → {@code containers/whole_<id>.contents.json}, the chest → loot-prefab links,
 *       keyed by {@link BlockVariantPlot#wholeKey}.</li>
 * </ul>
 * <b>{@code parts} and {@code contents-allow} are deliberately dropped.</b> A whole room is stamped
 * verbatim — no parts pass and no contents pass run over it — so there is no store that would ever read
 * them, and writing them would leave files behind that nothing cleans up. This is a real loss, and the
 * right one: a build that leans on its floor/wall parts is a carriage, and belongs in the carriage pool.
 *
 * <h2>The contents offset</h2>
 * A CONTENTS build is interior-only, and {@link BuilderRelayWholeRoom#bakeOntoDefaultShell} bakes it onto
 * the standard shell at {@code CarriageContentsPlacer.interiorOrigin}, which is {@code (+1, +1, +1)} from
 * the carriage corner. Its sidecars are in interior-local coordinates, so every cell key shifts by one on
 * every axis to become whole-carriage-local. A CARRIAGE build is already in the whole frame and shifts by
 * nothing. No crop is needed either way: an interior cell plus one is always inside the carriage box.
 *
 * <p>Pure enough to test without a world — {@link #translate} takes and returns strings.</p>
 */
public final class WholeRoomSidecars {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The document's map of role → file text, as {@code TemplateSidecars} writes it. */
    private static final String K_FILES = "files";

    /** The per-cell variant pools, under the source kind's variants role. */
    static final String ROLE_VARIANTS = "variants";

    /** The chest → loot-prefab links. Same role name on both sides. */
    static final String ROLE_CONTAINERS = TemplateSidecars.ROLE_CONTAINERS;

    /** The objects inside a sidecar document whose keys are {@code "x,y,z"} cells. */
    private static final String[] CELL_KEYED = {"variants", "pools", "links"};

    private WholeRoomSidecars() {}

    /**
     * Write {@code doc}'s translatable sidecars to whole room {@code id}. Never throws: a sidecar this
     * install cannot parse is one the room does without, and failing an install over it would cost the
     * build — the same posture {@link TemplateSidecars#apply} takes.
     */
    public static void apply(BuilderPhotoPaths.Kind kind, String id, String doc) {
        if (id == null || id.isEmpty() || doc == null || doc.isBlank()) return;
        JsonObject files;
        try {
            JsonObject root = JsonParser.parseString(doc).getAsJsonObject();
            if (!root.has(K_FILES) || !root.get(K_FILES).isJsonObject()) return;
            files = root.getAsJsonObject(K_FILES);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Whole room sidecars: '{}' carried a document that would not parse: {}",
                id, e.toString());
            return;
        }
        int shift = shiftFor(kind);
        write(id, text(files, ROLE_VARIANTS), shift,
            UserContentPaths.activeSubDir(WholeKind.ROOM.userSubdir()), id + WholeVariantBlocks.EXT,
            ROLE_VARIANTS);
        write(id, text(files, ROLE_CONTAINERS), shift,
            UserContentPaths.activeSubDir(ContainerContentsStore.SUBDIR),
            ContainerContentsStore.basenameFor(BlockVariantPlot.wholeKey(WholeKind.ROOM, id)),
            ROLE_CONTAINERS);
        // Both stores cache what they read, and a file written underneath one is invisible until it is
        // told. TemplateSidecars.invalidateCaches has no whole arm to do this for us.
        WholeVariantBlocks.invalidate(WholeKind.ROOM, id);
        ContainerContentsStore.invalidate(BlockVariantPlot.wholeKey(WholeKind.ROOM, id));
    }

    /** How far a build of {@code kind} sits from the whole-carriage frame, per axis. */
    static int shiftFor(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CONTENTS ? 1 : 0;
    }

    private static String text(JsonObject files, String role) {
        JsonElement el = files.get(role);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private static void write(String id, String source, int shift, Path dir, String basename, String role) {
        if (source.isEmpty()) return;
        String translated = translate(source, shift);
        if (translated.isEmpty()) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(basename), translated, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Whole room sidecars: could not write the {} sidecar for '{}': {}",
                role, id, e.toString());
        }
    }

    /**
     * Shift every {@code "x,y,z"} key in a sidecar document by {@code shift} on every axis, leaving the
     * rest of it — schema version, entry bodies, anything a later schema adds — exactly as it was.
     *
     * <p>Returns {@code ""} when the text will not parse, which the caller reads as "write nothing":
     * a half-translated sidecar would put variant pools on the wrong blocks, which is worse than none.
     * A {@code shift} of zero still round-trips through the parser, so a carriage build's document is
     * re-emitted rather than copied — one code path, and a malformed document is caught either way.</p>
     */
    static String translate(String source, int shift) {
        try {
            JsonObject root = JsonParser.parseString(source).getAsJsonObject();
            if (shift != 0) {
                for (String section : CELL_KEYED) {
                    if (!root.has(section) || !root.get(section).isJsonObject()) continue;
                    root.add(section, shiftKeys(root.getAsJsonObject(section), shift));
                }
            }
            return root.toString();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Whole room sidecars: a sidecar would not parse, skipping it: {}",
                e.toString());
            return "";
        }
    }

    /** One cell-keyed object with every key moved. A key that is not a cell is carried across untouched. */
    private static JsonObject shiftKeys(JsonObject section, int shift) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            out.add(shiftCell(entry.getKey(), shift), entry.getValue());
        }
        return out;
    }

    /** {@code "x,y,z"} moved by {@code shift} on every axis; anything else returned as it came. */
    static String shiftCell(String key, int shift) {
        String[] parts = key.split(",");
        if (parts.length != 3) return key;
        try {
            return (Integer.parseInt(parts[0].trim()) + shift) + ","
                 + (Integer.parseInt(parts[1].trim()) + shift) + ","
                 + (Integer.parseInt(parts[2].trim()) + shift);
        } catch (NumberFormatException e) {
            return key;
        }
    }
}
