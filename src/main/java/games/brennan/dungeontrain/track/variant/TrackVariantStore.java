package games.brennan.dungeontrain.track.variant;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.DeleteCachePolicy;
import games.brennan.dungeontrain.editor.ProvenanceCache;
import games.brennan.dungeontrain.editor.SourcePolicy;
import games.brennan.dungeontrain.editor.StoreCodecs;
import games.brennan.dungeontrain.editor.StoreDescriptor;
import games.brennan.dungeontrain.editor.TemplateNbt;
import games.brennan.dungeontrain.editor.ThreeTierStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Three-tier NBT store keyed on {@code (TrackKind, name)}, mirroring
 * {@link games.brennan.dungeontrain.editor.CarriagePartTemplateStore}. Each
 * kind has its own subdirectory (see {@link TrackKind#subdir()}) so
 * authoring a new track tile can never poison the tunnel registry, and so
 * a footprint mismatch in one kind doesn't break stamping in another.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/<subdir>/<name>.nbt}.
 *       Per-install override; the various track-side editors save here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/<subdir>/<name>.nbt}
 *       on the classpath. Optional. Shipped defaults live here.</li>
 *   <li><b>Empty</b> — caller falls back to legacy hardcoded geometry
 *       ({@link games.brennan.dungeontrain.tunnel.LegacyTunnelPaint},
 *       {@code TrackPalette.PILLAR}, etc.) so a brand-new install renders
 *       identically to the pre-feature world until templates are authored.</li>
 * </ol>
 *
 * <p>Each read is filtered against {@link TrackKind#dims(CarriageDims)} — a template authored at
 * one world's dims can't poison a different world. The gate runs <i>per call</i>, outside the
 * cache; a rejection at one caller's dims used to be cached as "absent" for every later caller
 * until the next reload.</p>
 *
 * <p>Policies: {@link SourcePolicy#NEVER} (editors promote explicitly), {@link DeleteCachePolicy#REMOVE}.
 * {@link #exists} asks the active package only.</p>
 */
public final class TrackVariantStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A variant's address. */
    private record Key(TrackKind kind, String name) {
        String id() {
            return kind.id() + "/" + name;
        }
    }

    private static final ThreeTierStore<Key, StructureTemplate> STORE =
        StoreDescriptor.<Key, StructureTemplate>named("TrackVariantStore")
            .id(Key::id)
            .subdir(k -> k.kind().subdir())
            .fileName(k -> k.name() + TrackKind.NBT_EXT)
            .bundledResource(k -> k.kind().bundledResourcePrefix() + k.name() + TrackKind.NBT_EXT)
            .sourceRelDir(k -> k.kind().sourceRelativePath())
            .codec(StoreCodecs.structureTemplate(false))
            .logNoun("track template")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .onSave(k -> ProvenanceCache.invalidateAll())
            .onDelete(k -> ProvenanceCache.invalidateAll())
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .build();

    private TrackVariantStore() {}

    /**
     * Write target for {@code kind} — the <i>active package's</i>
     * {@code <workingDir>/<subdir>/}, matching the directory
     * {@link UserContentPaths#findFile} reads from first.
     *
     * <p>This used to resolve straight to {@code config/dungeontrain/user/},
     * which is fine only while the unsaved package is active. Once a player
     * saved a package, reads came from {@code dtpacks/<pack>/} while writes
     * still went to {@code user/} — so edits (a removed block variant, a
     * re-saved room) silently failed to stick and the old content appeared to
     * come back. Resolving through the registry keeps the two halves pointed at
     * the same folder; with the unsaved package active it is byte-identical to
     * the old path.</p>
     */
    public static Path directory(TrackKind kind) {
        return UserContentPaths.activeSubDir(kind.subdir());
    }

    public static Path fileFor(TrackKind kind, String name) {
        return STORE.fileFor(new Key(kind, name));
    }

    public static String bundledResourceFor(TrackKind kind, String name) {
        return STORE.bundledResourceFor(new Key(kind, name));
    }

    /**
     * This variant's raw NBT, with no dims gate — for showing the template rather than stamping
     * it. See {@link TemplateNbt} for why the gate is skipped.
     */
    public static Optional<CompoundTag> rawTag(TrackKind kind, String name) {
        return TemplateNbt.read(
            kind.subdir(), name + TrackKind.NBT_EXT, bundledResourceFor(kind, name),
            "track template " + kind.id() + ":" + name);
    }

    public static Path sourceFileFor(TrackKind kind, String name) {
        return STORE.sourceFileFor(new Key(kind, name));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    /**
     * One-shot migration from legacy single-file template paths to the new
     * {@code <kind>/default.nbt} naming scheme. Covers every track-side kind:
     *
     * <ul>
     *   <li>{@code tracks/track.nbt} → {@code tracks/default.nbt}</li>
     *   <li>{@code pillars/pillar_top.nbt} → {@code pillars/top/default.nbt}
     *       (and {@code middle}, {@code bottom})</li>
     *   <li>{@code pillars/adjunct_stairs.nbt} → {@code pillars/adjunct_stairs/default.nbt}</li>
     *   <li>{@code tunnels/section.nbt} → {@code tunnels/section/default.nbt}
     *       (and {@code portal})</li>
     * </ul>
     *
     * <p>Idempotent: missing source = no-op, existing destination = no-op
     * (caller's per-install override wins). Logs at INFO when a move actually
     * happens; silent otherwise. Run from
     * {@link TrackVariantRegistry#reload()} on {@code ServerStartingEvent}
     * so the directory scan that follows picks up the renamed default.nbt as
     * the synthetic "default" entry instead of registering the legacy basename
     * as a custom variant.</p>
     */
    public static synchronized void migrateLegacyPaths() {
        Path configRoot = FMLPaths.CONFIGDIR.get().resolve("dungeontrain");

        moveLegacy(configRoot.resolve("tracks/track.nbt"),
            fileFor(TrackKind.TILE, TrackKind.DEFAULT_NAME), "track");

        moveLegacy(configRoot.resolve("pillars/pillar_top.nbt"),
            fileFor(TrackKind.PILLAR_TOP, TrackKind.DEFAULT_NAME), "pillar_top");
        moveLegacy(configRoot.resolve("pillars/pillar_middle.nbt"),
            fileFor(TrackKind.PILLAR_MIDDLE, TrackKind.DEFAULT_NAME), "pillar_middle");
        moveLegacy(configRoot.resolve("pillars/pillar_bottom.nbt"),
            fileFor(TrackKind.PILLAR_BOTTOM, TrackKind.DEFAULT_NAME), "pillar_bottom");
        moveLegacy(configRoot.resolve("pillars/adjunct_stairs.nbt"),
            fileFor(TrackKind.ADJUNCT_STAIRS, TrackKind.DEFAULT_NAME), "adjunct_stairs");

        moveLegacy(configRoot.resolve("tunnels/section.nbt"),
            fileFor(TrackKind.TUNNEL_SECTION, TrackKind.DEFAULT_NAME), "tunnel_section");
        moveLegacy(configRoot.resolve("tunnels/portal.nbt"),
            fileFor(TrackKind.TUNNEL_PORTAL, TrackKind.DEFAULT_NAME), "tunnel_portal");
    }

    private static void moveLegacy(Path legacy, Path target, String label) {
        if (!Files.isRegularFile(legacy)) return;
        if (Files.isRegularFile(target)) {
            LOGGER.info("[DungeonTrain] Legacy {} found at {} but default.nbt already exists at {} — skipping migration.",
                label, legacy, target);
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target);
            LOGGER.info("[DungeonTrain] Migrated legacy {} template {} -> {}", label, legacy, target);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to migrate {} -> {}: {}", legacy, target, e.toString());
        }
    }

    public static void invalidate(TrackKind kind, String name) {
        STORE.invalidate(new Key(kind, name));
    }

    /**
     * Load the NBT for {@code (kind, name)} — config dir first, then bundled
     * resource. Returns empty if neither tier has a template at the expected
     * footprint for {@code dims}. Cached per {@code (kind, name)}, ungated; the
     * footprint is judged on every call.
     */
    public static Optional<StructureTemplate> get(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        return filterForDims(kind, name, STORE.get(new Key(kind, name), level), dims);
    }

    /** Bundled-only load — used by {@code /dt reset} to revert a config-dir override. */
    public static Optional<StructureTemplate> getBundled(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        return filterForDims(kind, name, STORE.getBundled(new Key(kind, name), level), dims);
    }

    public static void save(TrackKind kind, String name, StructureTemplate template) throws IOException {
        STORE.save(new Key(kind, name), template);
    }

    public static void saveToSource(TrackKind kind, String name, StructureTemplate template) throws IOException {
        STORE.saveToSource(new Key(kind, name), template);
    }

    public static void promote(TrackKind kind, String name) throws IOException {
        STORE.promote(new Key(kind, name));
    }

    public static boolean delete(TrackKind kind, String name) throws IOException {
        return STORE.delete(new Key(kind, name));
    }

    /**
     * Move a saved track template to a new name, keeping its cache entry with it.
     *
     * <p>The file half of a rename only — the caller re-registers the name, exactly as
     * {@code CarriageTemplateStore.rename}'s callers do. Returns false when there is no config-dir
     * copy to move, which is what a bundled-only template looks like: those are shadowed rather than
     * renamed, and moving nothing is the honest answer.</p>
     */
    public static boolean rename(TrackKind kind, String sourceName, String targetName)
            throws IOException {
        return STORE.rename(new Key(kind, sourceName), new Key(kind, targetName));
    }

    public static boolean exists(TrackKind kind, String name) {
        return STORE.existsInActive(new Key(kind, name));
    }

    public static boolean bundled(TrackKind kind, String name) {
        return STORE.bundled(new Key(kind, name));
    }

    /**
     * The bounds a loaded template of {@code kind} is judged against — an exact size for most kinds,
     * a per-axis floor for a {@link TrackKind#freeSizeAboveFloor()} one.
     *
     * <p>Not {@link TrackKind#dims} for the free kind. That reports the <b>built-in</b> portal room's
     * footprint, which is a real instance rather than a limit, and borrowing it here made the built-in
     * room's width the minimum every authored room had to clear — a room could be rejected for being
     * narrower than a shell it has nothing to do with. {@code PortalRoomLayout.minSize} is the actual
     * floor: what the corridor mouth needs to stay sealed.</p>
     */
    private static Vec3i acceptableBounds(TrackKind kind, CarriageDims dims) {
        return kind.freeSizeAboveFloor() ? PortalRoomLayout.minSize(dims) : kind.dims(dims);
    }

    /**
     * Whether a loaded template's bounds are acceptable for {@code kind}.
     *
     * <p>Normally an exact match against {@link #acceptableBounds}. For a
     * {@link TrackKind#freeSizeAboveFloor()} kind those bounds are a <b>floor</b> — the author may go
     * bigger on any axis, and only undersize is rejected, because that is the case that breaks
     * something in world (a portal room too narrow or too short to seal the corridor mouth that opens
     * into it).</p>
     */
    private static boolean boundsMatch(TrackKind kind, Vec3i size, Vec3i bounds) {
        if (kind.freeSizeAboveFloor()) {
            return size.getX() >= bounds.getX()
                && size.getY() >= bounds.getY()
                && size.getZ() >= bounds.getZ();
        }
        return size.equals(bounds);
    }

    /** The size gate — per call, never cached. */
    private static Optional<StructureTemplate> filterForDims(
        TrackKind kind, String name, Optional<StructureTemplate> loaded, CarriageDims dims
    ) {
        if (loaded.isEmpty()) return loaded;
        Vec3i bounds = acceptableBounds(kind, dims);
        Vec3i size = loaded.get().getSize();
        if (boundsMatch(kind, size, bounds)) return loaded;
        LOGGER.warn(
            "[DungeonTrain] Track template {}:{} has bounds {}x{}x{}, expected {}x{}x{}{} — ignoring.",
            kind.id(), name, size.getX(), size.getY(), size.getZ(),
            bounds.getX(), bounds.getY(), bounds.getZ(),
            kind.freeSizeAboveFloor() ? " (minimum; larger is allowed)" : ""
        );
        return Optional.empty();
    }
}
