package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageWeights;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Duplicate everything a template knows about itself that is <b>not</b> its blocks.
 *
 * <p>The editor's <b>New</b> copies the template the player is standing in. The {@code .nbt} is the
 * caller's to save — it already has the geometry in hand — but the geometry alone is not the
 * template. Beside it sit the sidecars {@link TemplateSidecars#filesFor} enumerates (block variant
 * candidates, a carriage's parts, the contents allow-list, a portal room's copies, the chest → prefab
 * links) and its entry in the kind's {@code weights.json}. For a portal room that entry's
 * {@code mode} tag <em>is</em> the room's sky, walls, copies, exits and door settings, so a copy that
 * left it behind came up as a bare box under no sky.</p>
 *
 * <h2>Same list as rename and the relay</h2>
 * <p>The files are the ones {@link TrackVariantRename#moveSidecars} moves and the relay install
 * writes — one enumeration, three consumers — so a sidecar added to {@link TemplateSidecars} is
 * copied here without a change. Text goes across verbatim: these are the stores' own formats, and
 * re-encoding them would make this a second parser to keep in step with each of them.</p>
 *
 * <h2>Bundled sources</h2>
 * <p>A bundled template's sidecars live on the classpath, not in the config tree, so each role is
 * resolved config-first and then against {@code /data/dungeontrain/<subdir>/<basename>} — the same
 * fallback every store makes for itself. Copying {@code library_dimension} therefore carries its
 * variants and group-free settings just as copying a user room does.</p>
 *
 * <h2>Not carried</h2>
 * <ul>
 *   <li>The template's own {@code .group.json} — a copy that also owned the source's sub-variants
 *       would have those rooms rolling from two parents.</li>
 *   <li>Membership in other groups — the copy is not in that pool; {@code group new} adds it
 *       itself.</li>
 *   <li>The display label — two templates answering to one label are indistinguishable in every
 *       menu, so the copy is labelled by its own id until its author names it.</li>
 * </ul>
 */
public final class TemplateCopy {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String RESOURCE_ROOT = "/data/dungeontrain/";

    private TemplateCopy() {}

    /**
     * Lay down {@code to}'s sidecars and weights entry as copies of {@code from}'s.
     *
     * <p>Missing source sidecars are the ordinary case and are skipped. A failure writing one is
     * thrown so the caller can report it — the {@code .nbt} is already saved by then, and a template
     * with its blocks but not its variants is what this class exists to prevent.</p>
     *
     * @param subKind the part kind or track kind id for {@link BuilderPhotoPaths.Kind#PART} and
     *                {@link BuilderPhotoPaths.Kind#TRACK}; ignored otherwise
     */
    public static void copy(BuilderPhotoPaths.Kind kind, @Nullable String subKind,
                            String from, String to) throws IOException {
        if (kind == null || from == null || to == null) return;
        String src = from.toLowerCase(Locale.ROOT);
        String dst = to.toLowerCase(Locale.ROOT);
        if (src.equals(dst)) return;

        int copied = copyFiles(kind, subKind, src, dst);
        boolean weights = copyWeights(kind, subKind, src, dst);

        TemplateSidecars.invalidateCaches(kind, subKind, dst);
        ProvenanceCache.invalidateAll();
        LOGGER.info("[DungeonTrain] Copied template data {}:{} -> {} ({} sidecar file(s), weights entry: {})",
            kind.name().toLowerCase(Locale.ROOT), src, dst, copied, weights);
    }

    // ---------- sidecar files ----------

    /**
     * Copy every sidecar role, pairing the two {@link TemplateSidecars#filesFor} lists by index —
     * they are parallel (same kind, same roles, same order), which matters for the container
     * document whose basename is derived from the plot key rather than the id.
     *
     * @return how many files were written
     */
    private static int copyFiles(BuilderPhotoPaths.Kind kind, @Nullable String subKind,
                                 String from, String to) throws IOException {
        List<TemplateSidecars.Sidecar> before = TemplateSidecars.filesFor(kind, subKind, from);
        List<TemplateSidecars.Sidecar> after = TemplateSidecars.filesFor(kind, subKind, to);
        if (before.size() != after.size()) {
            LOGGER.warn("[DungeonTrain] Copy {}:{}: sidecar lists differ in size ({} vs {}) — skipping",
                kind, from, before.size(), after.size());
            return 0;
        }
        boolean devMode = EditorDevMode.isEnabled();
        int copied = 0;
        for (int i = 0; i < before.size(); i++) {
            TemplateSidecars.Sidecar oldSide = before.get(i);
            TemplateSidecars.Sidecar newSide = after.get(i);
            String text = readSource(oldSide);
            if (text == null) continue;
            write(UserContentPaths.activeSubDir(newSide.subdir()).resolve(newSide.basename()), text);
            if (devMode) {
                Path source = TrackVariantRename.sourceDir(newSide.subdir());
                if (source != null) write(source.resolve(newSide.basename()), text);
            }
            copied++;
        }
        return copied;
    }

    /**
     * The sidecar's text — the config tree first (user, then packages), then the bundled resource —
     * or null when the template has none of that role.
     */
    @Nullable
    private static String readSource(TemplateSidecars.Sidecar sidecar) throws IOException {
        Path onDisk = UserContentPaths.findFile(sidecar.subdir(), sidecar.basename());
        if (onDisk != null) {
            return Files.readString(onDisk, StandardCharsets.UTF_8);
        }
        return readBundled(sidecar);
    }

    /**
     * The sidecar as shipped in the jar, or null when the mod bundles none of that role for the
     * template. Package-private so the fallback — the one branch a config-dir test cannot reach —
     * can be checked against a real bundled resource.
     */
    @Nullable
    static String readBundled(TemplateSidecars.Sidecar sidecar) throws IOException {
        String resource = RESOURCE_ROOT + sidecar.subdir() + "/" + sidecar.basename();
        try (InputStream in = TemplateCopy.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    // ---------- weights entry ----------

    /**
     * Carry the kind's {@code weights.json} entry. Each store's own {@code copy} keeps everything but
     * the label, and handles its own config write and dev-mode source twin.
     *
     * @return whether the source had an entry to carry
     */
    private static boolean copyWeights(BuilderPhotoPaths.Kind kind, @Nullable String subKind,
                                       String from, String to) throws IOException {
        TrackKind trackKind = trackKindOf(kind, subKind);
        if (trackKind != null) {
            return TrackVariantWeights.copy(trackKind, from, to);
        }
        return switch (kind) {
            case CARRIAGE -> CarriageWeights.copy(from, to);
            case CONTENTS -> CarriageContentsWeights.copy(from, to);
            // Parts are weighted inside the carriage's own .parts.json, and a group is not weighted
            // at all — neither has an entry of its own to carry.
            default -> false;
        };
    }

    /** The {@link TrackKind} whose weights file holds this build, or null when it is not track-side. */
    @Nullable
    private static TrackKind trackKindOf(BuilderPhotoPaths.Kind kind, @Nullable String subKind) {
        if (kind == BuilderPhotoPaths.Kind.PORTAL_ROOM) return TrackKind.PORTAL_ROOM;
        return kind == BuilderPhotoPaths.Kind.TRACK && subKind != null ? TrackKind.fromId(subKind) : null;
    }
}
