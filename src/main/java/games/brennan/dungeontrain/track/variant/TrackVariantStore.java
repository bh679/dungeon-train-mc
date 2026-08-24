package games.brennan.dungeontrain.track.variant;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
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
 * <p>Each load is filtered against {@link TrackKind#dims(CarriageDims)} —
 * a template authored at one world's dims can't poison a different world.</p>
 */
public final class TrackVariantStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Key = {@code "<kind>/<name>"} — small flat map across all kinds. */
    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private TrackVariantStore() {}

    /**
     * Write target for {@code kind} — the <i>active package's</i>
     * {@code <workingDir>/<subdir>/}, matching the directory
     * {@link games.brennan.dungeontrain.editor.UserContentPaths#findFile} reads
     * from first.
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
        return games.brennan.dungeontrain.editor.UserContentPaths.activeSubDir(kind.subdir());
    }

    public static Path fileFor(TrackKind kind, String name) {
        return directory(kind).resolve(name + TrackKind.NBT_EXT);
    }

    public static String bundledResourceFor(TrackKind kind, String name) {
        return kind.bundledResourcePrefix() + name + TrackKind.NBT_EXT;
    }

    public static Path sourceFileFor(TrackKind kind, String name) {
        return sourceDirectory(kind).resolve(name + TrackKind.NBT_EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
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

    public static synchronized void invalidate(TrackKind kind, String name) {
        CACHE.remove(key(kind, name));
    }

    /**
     * Load the NBT for {@code (kind, name)} — config dir first, then bundled
     * resource. Returns empty if neither tier has a template at the expected
     * footprint for {@code dims}. Cached per {@code (kind, name)} —
     * dim-mismatch rebuilds drop the cache entry on the next get.
     */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        String key = key(kind, name);
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached != null) return filterForDims(kind, name, cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, kind, name, dims);
        if (loaded.isEmpty()) loaded = loadFromResource(level, kind, name, dims);
        CACHE.put(key, loaded);
        return loaded;
    }

    /** Bundled-only load — used by {@code /dt reset} to revert a config-dir override. */
    public static Optional<StructureTemplate> getBundled(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        return loadFromResource(level, kind, name, dims);
    }

    public static synchronized void save(TrackKind kind, String name, StructureTemplate template) throws IOException {
        Path dir = directory(kind);
        Files.createDirectories(dir);
        Path file = fileFor(kind, name);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        CACHE.put(key(kind, name), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved track template {}:{} to {}", kind.id(), name, file);
    }

    public static synchronized void saveToSource(TrackKind kind, String name, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(kind, name);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        LOGGER.info("[DungeonTrain] Wrote bundled track template {}:{} to {}", kind.id(), name, file);
    }

    public static synchronized void promote(TrackKind kind, String name) throws IOException {
        Path src = fileFor(kind, name);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved track template for " + kind.id() + ":" + name + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(kind, name);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted track template {}:{} from {} to {}", kind.id(), name, src, dst);
    }

    public static synchronized boolean delete(TrackKind kind, String name) throws IOException {
        Path file = fileFor(kind, name);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(key(kind, name), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted track template {}:{} ({})", kind.id(), name, file);
        return existed;
    }

    public static boolean exists(TrackKind kind, String name) {
        return Files.isRegularFile(fileFor(kind, name));
    }

    public static boolean bundled(TrackKind kind, String name) {
        try (InputStream in = TrackVariantStore.class.getResourceAsStream(bundledResourceFor(kind, name))) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<StructureTemplate> loadFromConfig(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        // Search user/<kind.subdir>/ first, then each imported/<pkg>/<kind.subdir>/.
        Path file = games.brennan.dungeontrain.editor.UserContentPaths.findFile(
            kind.subdir(), name + TrackKind.NBT_EXT);
        if (file == null) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, kind, name, dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config track template {}:{} at {}: {}",
                kind.id(), name, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims
    ) {
        String resource = bundledResourceFor(kind, name);
        try (InputStream in = TrackVariantStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, kind, name, dims, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled track template {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, TrackKind kind, String name, CarriageDims dims,
        CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i bounds = acceptableBounds(kind, dims);
        Vec3i size = template.getSize();
        if (!boundsMatch(kind, size, bounds)) {
            LOGGER.warn(
                "[DungeonTrain] Track template {}:{} ({}) has bounds {}x{}x{}, expected {}x{}x{}{} — ignoring.",
                kind.id(), name, origin, size.getX(), size.getY(), size.getZ(),
                bounds.getX(), bounds.getY(), bounds.getZ(),
                kind.freeSizeAboveFloor() ? " (minimum; larger is allowed)" : ""
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded track template {}:{} from {}", kind.id(), name, origin);
        return Optional.of(template);
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

    private static Optional<StructureTemplate> filterForDims(
        TrackKind kind, String name, Optional<StructureTemplate> cached, CarriageDims dims
    ) {
        if (cached.isEmpty()) return cached;
        if (boundsMatch(kind, cached.get().getSize(), acceptableBounds(kind, dims))) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached track template {}:{} no longer matches dims {}x{}x{} — falling back.",
            kind.id(), name, dims.length(), dims.width(), dims.height()
        );
        return Optional.empty();
    }

    private static String key(TrackKind kind, String name) {
        return kind.id() + "/" + name;
    }

    private static Path sourceDirectory(TrackKind kind) {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return projectRoot.resolve(kind.sourceRelativePath());
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static Path projectRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir.getParent();
    }
}
