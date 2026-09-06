package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rename a track-side template — a portal room, a track tile, a tunnel, a pillar section.
 *
 * <h2>Why this is not just a file move</h2>
 * <p>A template's name is its identity, and four other documents spell that identity out: the
 * sidecars beside it ({@code .variants.json}, a room's {@code contents-allow} and {@code copies},
 * the container document filed under the plot key), the group sidecar it may own, the
 * {@code weights.json} entry carrying its weight, gate, Stage link and mode — and the member list of
 * whatever group it belongs to. Move the {@code .nbt} alone and the room keeps its blocks and loses
 * everything that made it what it was: its loot, its variants, its place in the group that rolls it.</p>
 *
 * <h2>Order</h2>
 * <p>Files first, memory last. The registry swap is the final step, so a rename that fails partway
 * leaves the old name still registered and still the one every other document names — recoverable by
 * hand, rather than a room that exists under a name nothing points at. {@link Result} says which step
 * refused; callers turn it into a player-facing line.</p>
 *
 * <h2>Bundled templates</h2>
 * <p>A template that exists only as a bundled resource is <b>shadowed</b> by a config-dir copy, never
 * moved — there is nothing on disk to move, and renaming it would leave the bundled original sitting
 * under the old name. That case is {@link Result#NO_CONFIG_COPY}, which is a refusal and not an error.</p>
 *
 * @see games.brennan.dungeontrain.builder.relay.BuilderRelayInstall which does the template+registry
 *      half of this on the download path, where the download brings its own sidecars
 */
public final class TrackVariantRename {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What a rename attempt did, or why it did nothing. */
    public enum Result {
        OK,
        /** The name typed is not a legal template name. */
        BAD_NAME,
        /** Source and target are the same name. */
        SAME_NAME,
        /** {@code default} is the kind's fallback and is never renamed in either direction. */
        RESERVED,
        /** Nothing is registered under the source name. */
        UNKNOWN,
        /** Something already answers to the target name. */
        TAKEN,
        /** Bundled-only: there is no config-dir file to move. */
        NO_CONFIG_COPY
    }

    private TrackVariantRename() {}

    /**
     * Move {@code from} to {@code to}, carrying everything that names it.
     *
     * @throws IOException if a file move fails partway; the log names the step
     */
    public static synchronized Result rename(TrackKind kind, String from, String to) throws IOException {
        if (kind == null || from == null || to == null) return Result.BAD_NAME;
        String src = from.trim().toLowerCase(Locale.ROOT);
        String dst = to.trim().toLowerCase(Locale.ROOT);
        if (!TrackVariantRegistry.NAME_PATTERN.matcher(dst).matches()) return Result.BAD_NAME;
        if (src.equals(dst)) return Result.SAME_NAME;
        if (TrackKind.DEFAULT_NAME.equals(src) || TrackKind.DEFAULT_NAME.equals(dst)) return Result.RESERVED;
        if (!TrackVariantRegistry.contains(kind, src)) return Result.UNKNOWN;
        if (TrackVariantRegistry.contains(kind, dst) || TrackVariantStore.exists(kind, dst)) return Result.TAKEN;

        if (!TrackVariantStore.rename(kind, src, dst)) return Result.NO_CONFIG_COPY;
        boolean devMode = EditorDevMode.isEnabled();
        if (devMode) moveSourceTemplate(kind, src, dst);

        moveSidecars(kind, src, dst, devMode);
        moveGroupSidecar(kind, src, dst, devMode);
        rewriteMemberships(kind, src, dst, devMode);
        TrackVariantWeights.rename(kind, src, dst);

        // Last, and deliberately: everything above still names `src`, so a failure before this point
        // leaves the old name registered and the pieces findable.
        TrackVariantRegistry.register(kind, dst);
        TrackVariantRegistry.unregister(kind, src);
        invalidateCaches(kind, src, dst);

        LOGGER.info("[DungeonTrain] Renamed track template {}:{} -> {}:{} (dev source write-through: {})",
            kind.id(), src, kind.id(), dst, devMode);
        return Result.OK;
    }

    // ---------- sidecars ----------

    /**
     * Move every sidecar the template could have, by asking {@link TemplateSidecars} for both names.
     *
     * <p>The two lists are parallel — same kind, same roles, same order — so a sidecar's new home is
     * its counterpart rather than a filename this class rebuilds. That matters for the container
     * document, whose basename is derived from the plot key and not from the id.</p>
     */
    private static void moveSidecars(TrackKind kind, String from, String to, boolean devMode) throws IOException {
        BuilderPhotoPaths.Kind photoKind = kind == TrackKind.PORTAL_ROOM
            ? BuilderPhotoPaths.Kind.PORTAL_ROOM : BuilderPhotoPaths.Kind.TRACK;
        List<TemplateSidecars.Sidecar> before = TemplateSidecars.filesFor(photoKind, kind.id(), from);
        List<TemplateSidecars.Sidecar> after = TemplateSidecars.filesFor(photoKind, kind.id(), to);
        if (before.size() != after.size()) {
            LOGGER.warn("[DungeonTrain] Rename {}:{}: sidecar lists differ in size ({} vs {}) — skipping",
                kind.id(), from, before.size(), after.size());
            return;
        }
        for (int i = 0; i < before.size(); i++) {
            TemplateSidecars.Sidecar oldSide = before.get(i);
            TemplateSidecars.Sidecar newSide = after.get(i);
            Path existing = UserContentPaths.findFile(oldSide.subdir(), oldSide.basename());
            if (existing != null) {
                move(existing, UserContentPaths.activeSubDir(newSide.subdir()).resolve(newSide.basename()));
            }
            if (devMode) {
                Path source = sourceDir(oldSide.subdir());
                if (source != null) move(source.resolve(oldSide.basename()), source.resolve(newSide.basename()));
            }
        }
    }

    /** The template's own {@code .group.json}, which {@link TemplateSidecars} does not list. */
    private static void moveGroupSidecar(TrackKind kind, String from, String to, boolean devMode) throws IOException {
        move(TrackVariantGroupStore.fileFor(kind, from), TrackVariantGroupStore.fileFor(kind, to));
        if (devMode && TrackVariantGroupStore.sourceTreeAvailable()) {
            move(TrackVariantGroupStore.sourceFileFor(kind, from), TrackVariantGroupStore.sourceFileFor(kind, to));
        }
        TrackVariantGroupStore.invalidate(kind, from);
        TrackVariantGroupStore.invalidate(kind, to);
    }

    /**
     * Rewrite every group of this kind that holds {@code from} as a member.
     *
     * <p>The member keeps its slot, its weight, its gate and its Stage links — a rename is not an
     * occasion to re-roll how often the room comes up. A group that does not hold it is left
     * untouched, including its file's modification time.</p>
     */
    private static void rewriteMemberships(TrackKind kind, String from, String to, boolean devMode)
            throws IOException {
        for (String parent : TrackVariantRegistry.namesFor(kind)) {
            TrackVariantGroup group = TrackVariantGroupStore.get(kind, parent).orElse(null);
            if (group == null || group.indexOf(from) < 0) continue;
            TrackVariantGroup renamed = withMemberRenamed(group, from, to);
            TrackVariantGroupStore.save(kind, parent, renamed);
            if (devMode && TrackVariantGroupStore.sourceTreeAvailable()) {
                TrackVariantGroupStore.saveToSource(kind, parent, renamed);
            }
            LOGGER.info("[DungeonTrain] Rename {}:{} -> {}: rewrote membership in group '{}'",
                kind.id(), from, to, parent);
        }
    }

    /**
     * {@code group} with member {@code from} renamed to {@code to} — same slot, same weight, same
     * gate, same Stage links.
     *
     * <p>Pure, and separate from the file work above so the one thing that could silently change how
     * often a room comes up is testable without a server. A group that does not hold {@code from} is
     * returned unchanged, so callers can hand it any group.</p>
     */
    static TrackVariantGroup withMemberRenamed(TrackVariantGroup group, String from, String to) {
        if (group == null || group.indexOf(from) < 0) return group;
        List<TrackVariantGroup.Member> next = new ArrayList<>(group.members().size());
        for (TrackVariantGroup.Member m : group.members()) {
            next.add(m.id().equals(from.toLowerCase(Locale.ROOT))
                ? new TrackVariantGroup.Member(to, m.weight(), m.gate(), m.stageIds())
                : m);
        }
        return new TrackVariantGroup(group.selfWeight(), next);
    }

    // ---------- caches ----------

    /**
     * Drop both names from every cache that keys on one.
     *
     * <p>Both, not just the old one: a cache entry for the new name can already exist — a menu that
     * asked about it before the move would have cached the empty answer — and serving that would
     * report the renamed template as having no variants at all.</p>
     */
    private static void invalidateCaches(TrackKind kind, String from, String to) {
        TrackVariantBlocks.invalidate(kind, from);
        TrackVariantBlocks.invalidate(kind, to);
        ContainerContentsStore.invalidate(ContainerContentsStore.trackPlotKey(kind, from));
        ContainerContentsStore.invalidate(ContainerContentsStore.trackPlotKey(kind, to));
        if (kind == TrackKind.PORTAL_ROOM) {
            PortalRoomContentsAllowStore.invalidate(from);
            PortalRoomContentsAllowStore.invalidate(to);
            PortalRoomCopiesVariant.invalidate(from);
            PortalRoomCopiesVariant.invalidate(to);
            // The room's size is filed under its name; drop the stale entry so the next read measures
            // the template rather than trusting a number left behind by the old one.
            PortalRoomSizes.forget(from);
        }
    }

    // ---------- files ----------

    /** Move {@code src} to {@code dst} when it exists. Missing source is the ordinary case. */
    private static void move(@Nullable Path src, Path dst) throws IOException {
        if (src == null || !Files.isRegularFile(src)) return;
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The template file's twin in the source tree — dev mode only; see {@link EditorDevMode}. */
    private static void moveSourceTemplate(TrackKind kind, String from, String to) throws IOException {
        if (!TrackVariantStore.sourceTreeAvailable()) return;
        move(TrackVariantStore.sourceFileFor(kind, from), TrackVariantStore.sourceFileFor(kind, to));
    }

    /** {@code src/main/resources/data/dungeontrain/<subdir>}, or null when there is no source tree. */
    @Nullable
    private static Path sourceDir(String subdir) {
        Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        Path projectRoot = gameDir == null ? null : gameDir.getParent();
        if (projectRoot == null) return null;
        Path dir = projectRoot.resolve("src/main/resources/data/dungeontrain").resolve(subdir);
        return Files.isDirectory(dir) ? dir : null;
    }
}
