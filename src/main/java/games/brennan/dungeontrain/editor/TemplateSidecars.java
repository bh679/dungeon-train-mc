package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageWeights;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a template knows about itself that is <b>not</b> its blocks, gathered into one document
 * a build can carry between installs — and put back on the other side.
 *
 * <p>A template on disk is an {@code .nbt} plus a small family of sidecar files beside it: the block
 * variant candidates, a carriage's part assignments, a contents allow-list, a portal room's copies
 * variant, the chest → loot-prefab links, and its entry in the kind's shared {@code weights.json}.
 * The relay used to carry the {@code .nbt} alone, so a downloaded build arrived as geometry with all
 * of its authored behaviour stripped — most visibly a portal room, whose doorway offsets live in the
 * weights entry's {@code mode} tag and so came back at dead centre.</p>
 *
 * <h2>Roles, not filenames</h2>
 * <p>The document keys each file by its <b>role</b> ({@code variants}, {@code parts}, …) rather than
 * by its filename, because a filename contains the template's id and a download may be installed
 * under a different one ({@code Load as new}, or a rename around a collision). Roles are resolved to
 * paths against the id the build actually lands under, so a renamed install still files its sidecars
 * beside its own {@code .nbt} rather than beside the template it was copied from.</p>
 *
 * <h2>Verbatim text, the stores' own paths</h2>
 * <p>Files travel as their exact text: they are the stores' formats, versioned by the stores, and
 * re-encoding them here would make this class a second parser to keep in step with each of them. The
 * paths, though, are never re-spelled — every subdirectory and extension below is the owning store's
 * own constant, so a store that moves takes this with it. Reads go through
 * {@link UserContentPaths#findFile} and writes through {@link UserContentPaths#activeSubDir}, which
 * is exactly how the stores themselves reach the active package.</p>
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li><b>Loot prefabs and contents-pool definitions</b> — library objects shared by every template,
 *       not sidecars of one. The links to them travel; the prefabs themselves do not, because
 *       installing a build must never overwrite an unrelated local prefab.</li>
 *   <li><b>The stage link</b> — it has its own relay field and its own install step.</li>
 *   <li><b>The {@code .nbt}</b> — that is the build, and it travels as the blocks blob.</li>
 * </ul>
 */
public final class TemplateSidecars {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Refuse to carry a document past this. Sidecars are id lists and small maps — a document near
     * this size is a runaway store, not a build, and the relay caps the field at its own end anyway.
     * Skipping is safe in a way truncating never is: the build still uploads, with no sidecars.
     */
    static final int MAX_DOC_CHARS = 200_000;

    private static final String K_FILES = "files";
    private static final String K_WEIGHTS = "weights";

    private TemplateSidecars() {}

    /** One sidecar file: the role it plays, and where that role lives for a given template. */
    public record Sidecar(String role, String subdir, String basename) {}

    // ---- what a kind's sidecars are ----

    /**
     * Every sidecar file template {@code id} of {@code kind} could have, whether or not it exists.
     *
     * <p>Empty for a kind with none, and for a {@link BuilderPhotoPaths.Kind#PART} or
     * {@link BuilderPhotoPaths.Kind#TRACK} whose {@code subKind} does not name a real id-space —
     * the same refusal-rather-than-guess {@code BuilderRelayInstall} makes about those two kinds,
     * for the same reason: {@code standard} is both a floor and a door.</p>
     */
    public static List<Sidecar> filesFor(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        List<Sidecar> out = new ArrayList<>();
        if (kind == null || id == null || id.isEmpty()) return out;
        switch (kind) {
            case CARRIAGE -> {
                out.add(new Sidecar("variants", CarriageVariantBlocks.SUBDIR,
                        id + CarriageVariantBlocks.EXT));
                out.add(new Sidecar("parts", CarriageVariantPartsStore.SUBDIR,
                        id + CarriageVariantPartsStore.EXT));
                out.add(new Sidecar("contents-allow", CarriageVariantBlocks.SUBDIR,
                        id + ContentsAllowStore.EXT));
                out.add(containers("carriage:" + id));
            }
            case CONTENTS -> {
                out.add(new Sidecar("variants", CarriageContentsVariantBlocks.SUBDIR,
                        id + CarriageContentsVariantBlocks.EXT));
                out.add(containers("contents:" + id));
            }
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                if (partKind == null) return out;
                out.add(new Sidecar("variants",
                        CarriagePartVariantBlocks.SUBDIR_BASE + "/" + partKind.id(),
                        id + CarriagePartVariantBlocks.EXT));
                out.add(containers("part:" + partKind.id() + ":" + id));
            }
            case TRACK -> {
                TrackKind trackKind = TrackKind.fromId(subKind);
                if (trackKind == null) return out;
                out.add(new Sidecar("variants", trackKind.subdir(), id + TrackKind.VARIANTS_EXT));
                out.add(containers(ContainerContentsStore.trackPlotKey(trackKind, id)));
            }
            case PORTAL_ROOM -> {
                TrackKind room = TrackKind.PORTAL_ROOM;
                out.add(new Sidecar("variants", room.subdir(), id + TrackKind.VARIANTS_EXT));
                out.add(new Sidecar("contents-allow", room.subdir(), id + ContentsAllowStore.EXT));
                out.add(new Sidecar("copies", room.subdir(), id + PortalRoomCopiesVariant.COPIES_EXT));
                out.add(containers(ContainerContentsStore.trackPlotKey(room, id)));
            }
            // A group is a list of carriage ids and nothing else — its members carry their own.
            case CARRIAGE_GROUP -> { }
        }
        return out;
    }

    private static Sidecar containers(String plotKey) {
        return new Sidecar("containers", ContainerContentsStore.SUBDIR,
                ContainerContentsStore.basenameFor(plotKey));
    }

    // ---- collect (upload side) ----

    /**
     * The document for template {@code id}, or {@code ""} when it has nothing to say.
     *
     * <p>Never throws: a sidecar this install cannot read is one the download will do without, and
     * failing the upload over it would cost the build. Same posture as the oversize case.</p>
     */
    public static String collect(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        JsonObject files = new JsonObject();
        for (Sidecar sidecar : filesFor(kind, subKind, id)) {
            Path path = UserContentPaths.findFile(sidecar.subdir(), sidecar.basename());
            if (path == null) continue;
            try {
                files.add(sidecar.role(), new JsonPrimitive(Files.readString(path, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Template sidecars: could not read {} for '{}': {}",
                        path, id, e.toString());
            }
        }
        JsonElement weights = weightsEntry(kind, subKind, id);

        JsonObject doc = new JsonObject();
        if (files.size() > 0) doc.add(K_FILES, files);
        if (weights != null) doc.add(K_WEIGHTS, weights);
        if (doc.size() == 0) return "";
        String text = doc.toString();
        if (text.length() > MAX_DOC_CHARS) {
            LOGGER.info("[DungeonTrain] Template sidecars: '{}' is {} chars, over the {} limit — "
                    + "uploading the build without them.", id, text.length(), MAX_DOC_CHARS);
            return "";
        }
        return text;
    }

    /**
     * This template's {@code weights.json} entry, in the codec's own shape, or null for a kind with
     * no weights entry.
     *
     * <p>Through {@link TemplateWeightCodec} rather than hand-written JSON so the wire form is the
     * file form: a value this class writes is one the store's own reader accepts, and a field added
     * to the entry format is carried without a change here. The stage link is stripped on the way
     * out — it travels as its own relay field, and leaving it in would give an install two sources
     * for it that could disagree.</p>
     */
    private static JsonElement weightsEntry(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        TemplateMeta meta = metaOf(kind, subKind, id);
        return meta == null ? null : encodeWeights(id, meta);
    }

    /**
     * One template's weights entry as the codec would write it into {@code weights.json}, with the
     * stage link stripped.
     *
     * <p>Split out from {@link #weightsEntry} so the encoding can be tested without a world, which is
     * worth doing for one reason above the rest: a portal room's <b>door position</b> is carried in
     * this entry's {@code mode} tag and nowhere else. If it does not survive here it does not survive
     * at all, and nothing downstream would complain — the room would simply install with its doorway
     * back at dead centre.</p>
     *
     * <p>{@code id} is only the key the codec writes under; the caller takes the value straight back
     * out, so a build installed under a different name is unaffected.</p>
     */
    static JsonElement encodeWeights(String id, TemplateMeta meta) {
        return TemplateWeightCodec.toJson(Map.of(id, meta.withStage(""))).get(id);
    }

    /** The inverse of {@link #encodeWeights} — what {@link #applyWeights} writes back to the store. */
    static TemplateMeta decodeWeights(JsonElement entry) {
        return TemplateWeightCodec.parseEntry(entry, CarriageWeights::clamp);
    }

    /** What the kind's weight store currently holds for {@code id}, or null when it keeps none. */
    private static TemplateMeta metaOf(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        TrackKind trackKind = trackKindOf(kind, subKind);
        if (trackKind != null) {
            return new TemplateMeta(
                    TrackVariantWeights.weightFor(trackKind, id),
                    TrackVariantWeights.gateFor(trackKind, id),
                    TrackVariantWeights.stageIdFor(trackKind, id),
                    TrackVariantWeights.modeFor(trackKind, id));
        }
        return switch (kind) {
            case CARRIAGE -> new TemplateMeta(CarriageWeights.current().weightFor(id),
                    CarriageWeights.current().gateFor(id), CarriageWeights.current().stageIdFor(id));
            case CONTENTS -> new TemplateMeta(CarriageContentsWeights.current().weightFor(id),
                    CarriageContentsWeights.current().gateFor(id),
                    CarriageContentsWeights.current().stageIdFor(id));
            // Parts are weighted inside the carriage's own .parts.json, and a group is not weighted
            // at all — neither has an entry of its own to carry.
            default -> null;
        };
    }

    /** The {@link TrackKind} whose weights file holds this build, or null when it is not track-side. */
    private static TrackKind trackKindOf(BuilderPhotoPaths.Kind kind, String subKind) {
        if (kind == BuilderPhotoPaths.Kind.PORTAL_ROOM) return TrackKind.PORTAL_ROOM;
        return kind == BuilderPhotoPaths.Kind.TRACK ? TrackKind.fromId(subKind) : null;
    }

    // ---- apply (install side) ----

    /**
     * Write {@code doc}'s sidecars for template {@code id}, which has just been installed.
     *
     * <p>Blank does nothing — that is what a build uploaded before this existed, or fetched from a
     * relay that does not carry the field, hands over. It must leave whatever this install already
     * has alone rather than resetting it to defaults.</p>
     *
     * <p>Per role and per field, each failure is logged and stepped over: a build that arrives with
     * one unreadable sidecar is better installed without it than not installed at all, and the
     * template itself is already on disk by the time this runs.</p>
     */
    public static void apply(BuilderPhotoPaths.Kind kind, String subKind, String id, String doc) {
        if (doc == null || doc.isBlank()) return;
        JsonObject root;
        try {
            root = JsonParser.parseString(doc).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Template sidecars: '{}' came with a document that would not "
                    + "parse — installed without them: {}", id, e.toString());
            return;
        }
        applyFiles(kind, subKind, id, root);
        applyWeights(kind, subKind, id, root);
    }

    private static void applyFiles(BuilderPhotoPaths.Kind kind, String subKind, String id,
                                   JsonObject root) {
        if (!root.has(K_FILES) || !root.get(K_FILES).isJsonObject()) return;
        JsonObject files = root.getAsJsonObject(K_FILES);
        // Resolved against the id the build actually landed under, never the one it was uploaded as.
        Map<String, Sidecar> byRole = new LinkedHashMap<>();
        for (Sidecar sidecar : filesFor(kind, subKind, id)) byRole.put(sidecar.role(), sidecar);
        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            Sidecar sidecar = byRole.get(entry.getKey());
            // A role this kind does not have here — a build uploaded by a newer mod, or by a kind
            // whose sub kind this install cannot resolve. Skipped, not an error.
            if (sidecar == null || !entry.getValue().isJsonPrimitive()) continue;
            try {
                Path dir = UserContentPaths.activeSubDir(sidecar.subdir());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(sidecar.basename()), entry.getValue().getAsString(),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Template sidecars: could not write the {} sidecar for "
                        + "'{}': {}", sidecar.role(), id, e.toString());
            }
        }
        // The stores cache what they read; a file written underneath them has to be announced.
        invalidateCaches(kind, subKind, id);
    }

    private static void applyWeights(BuilderPhotoPaths.Kind kind, String subKind, String id,
                                     JsonObject root) {
        if (!root.has(K_WEIGHTS)) return;
        TemplateMeta meta;
        try {
            meta = decodeWeights(root.get(K_WEIGHTS));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Template sidecars: '{}' came with a weights entry that would "
                    + "not parse: {}", id, e.toString());
            return;
        }
        if (meta == null) return;
        try {
            TrackKind trackKind = trackKindOf(kind, subKind);
            if (trackKind != null) {
                TrackVariantWeights.set(trackKind, id, meta.weight());
                TrackVariantWeights.setGate(trackKind, id, meta.gate());
                // The last of the three on purpose: this is the one carrying a portal room's door
                // position, and the two above rewrite the same entry.
                TrackVariantWeights.setMode(trackKind, id, meta.mode());
                return;
            }
            switch (kind) {
                case CARRIAGE -> {
                    CarriageWeights.set(id, meta.weight());
                    CarriageWeights.setGate(id, meta.gate());
                }
                case CONTENTS -> {
                    CarriageContentsWeights.set(id, meta.weight());
                    CarriageContentsWeights.setGate(id, meta.gate());
                }
                default -> { }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Template sidecars: could not write the weights entry for "
                    + "'{}': {}", id, e.toString());
        }
    }

    /**
     * Drop whatever the sidecar stores have cached for this template.
     *
     * <p>Load-bearing when a download {@code Replace}s a template the editor has already opened this
     * session: a store would otherwise keep serving the copy it read before the file changed
     * underneath it, and the build would look like it installed without its variants. Per template
     * rather than a cache-wide clear — nothing else on this install changed.</p>
     */
    private static void invalidateCaches(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        TrackKind trackKind = trackKindOf(kind, subKind);
        if (trackKind != null) {
            TrackVariantBlocks.invalidate(trackKind, id);
            ContainerContentsStore.invalidate(ContainerContentsStore.trackPlotKey(trackKind, id));
        }
        switch (kind) {
            case CARRIAGE -> {
                CarriageVariantBlocks.invalidate(id);
                CarriageVariantPartsStore.invalidate(id);
                CarriageVariantContentsAllowStore.invalidate(id);
                ContainerContentsStore.invalidate("carriage:" + id);
            }
            case CONTENTS -> {
                CarriageContentsVariantBlocks.invalidate(id);
                ContainerContentsStore.invalidate("contents:" + id);
            }
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                if (partKind == null) return;
                CarriagePartVariantBlocks.invalidate(partKind, id);
                ContainerContentsStore.invalidate("part:" + partKind.id() + ":" + id);
            }
            case PORTAL_ROOM -> {
                PortalRoomContentsAllowStore.invalidate(id);
                PortalRoomCopiesVariant.invalidate(id);
            }
            case TRACK, CARRIAGE_GROUP -> { }
        }
    }

    /** Whether {@code kind} has any sidecar at all — what a caller checks before bothering. */
    public static boolean carries(BuilderPhotoPaths.Kind kind) {
        return kind != null && kind != BuilderPhotoPaths.Kind.CARRIAGE_GROUP;
    }
}
