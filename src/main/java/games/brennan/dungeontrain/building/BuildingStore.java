package games.brennan.dungeontrain.building;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.DoubleBlockTemplateRepair;
import games.brennan.dungeontrain.editor.SourceTreeFiles;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Where building templates live.
 *
 * <ol>
 *   <li><b>Player</b> — {@code <user>/<package>/buildings/<name>.nbt} across every enabled package
 *       ({@link UserContentPaths#findFile}). What the editor writes, and what worldgen places ahead of the
 *       jar's copy. Nothing here when the world has turned custom content off.</li>
 *   <li><b>Shipped</b> — {@code /data/dungeontrain/structure/lost_city/<name>.nbt}: DT's own Lost City
 *       buildings.</li>
 *   <li><b>Bundled new</b> — {@code /data/dungeontrain/structure/buildings/<name>.nbt}: buildings authored in
 *       the editor in dev mode, which ship through the new-building slot.</li>
 * </ol>
 *
 * <p>Holds no loaded copies: worldgen's {@code StructureTemplateManager} caches the templates it places, and
 * the editor reads a file only when it stamps a plot.</p>
 */
public final class BuildingStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXT = ".nbt";
    static final String SHIPPED_RESOURCE = "/data/dungeontrain/structure/" + Buildings.SHIPPED_PREFIX;
    static final String BUNDLED_NEW_RESOURCE = "/data/dungeontrain/structure/" + Buildings.PLAYER_PREFIX;
    private static final String SHIPPED_SOURCE = "src/main/resources/data/dungeontrain/structure/lost_city";
    private static final String BUNDLED_NEW_SOURCE = "src/main/resources/data/dungeontrain/structure/buildings";

    private static volatile Set<String> shipped;

    private BuildingStore() {}

    /** True when {@code name} is one of DT's shipped Lost City buildings. */
    public static boolean isShipped(String name) {
        return name != null && shippedNames().contains(name);
    }

    /** The shipped building names. */
    public static Set<String> shippedNames() {
        Set<String> names = shipped;
        if (names == null) {
            names = Set.copyOf(BundledNbtScanner.scanBasenames(BuildingStore.class, SHIPPED_RESOURCE, LOGGER));
            shipped = names;
        }
        return names;
    }

    /** New buildings the jar ships (dev-mode authored), sorted. */
    public static Set<String> bundledNewNames() {
        return BundledNbtScanner.scanBasenames(BuildingStore.class, BUNDLED_NEW_RESOURCE, LOGGER);
    }

    /** True when the jar has a copy of {@code name} — shipped or bundled new. */
    public static boolean isBundled(String name) {
        return bundledResource(name) != null;
    }

    /** The player's file for {@code name}, or null when they have none (or custom content is off). */
    public static Path playerFile(String name) {
        return UserContentPaths.findFile(Buildings.SUBDIR, name + EXT);
    }

    /** True when the player has their own copy of {@code name}. */
    public static boolean hasPlayerCopy(String name) {
        return playerFile(name) != null;
    }

    /** The file a save of {@code name} writes. */
    public static Path writeFileFor(String name) {
        return UserContentPaths.activeSubDir(Buildings.SUBDIR).resolve(name + EXT);
    }

    /** The source-tree path a dev-mode save writes, or null outside a writable checkout. */
    public static Path sourceFileFor(String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(isShipped(name) ? SHIPPED_SOURCE : BUNDLED_NEW_SOURCE).resolve(name + EXT);
    }

    /** {@code name}'s raw NBT — the player's copy first, then the jar's — or empty. */
    public static Optional<CompoundTag> readTag(String name) {
        Optional<CompoundTag> player = readPlayerTag(name);
        return player.isPresent() ? player : readBundledTag(name);
    }

    /** The player's copy of {@code name}, or empty. */
    public static Optional<CompoundTag> readPlayerTag(String name) {
        Path file = playerFile(name);
        if (file == null) return Optional.empty();
        try {
            return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read building {} at {}: {}", name, file, e.toString());
            return Optional.empty();
        }
    }

    /** The jar's copy of {@code name} — what a reset to the shipped version stamps — or empty. */
    public static Optional<CompoundTag> readBundledTag(String name) {
        String resource = bundledResource(name);
        if (resource == null) return Optional.empty();
        try (InputStream in = BuildingStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            return Optional.of(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled building {}: {}", resource, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Write {@code tag} (a saved structure) as {@code name}, and to the source tree when {@code toSource}.
     * Callers evict the building from worldgen's template cache afterwards ({@link BuildingWorldgen#evict}).
     */
    public static void save(String name, CompoundTag tag, boolean toSource) throws IOException {
        CompoundTag repaired = DoubleBlockTemplateRepair.repair(tag, "save");
        Path file = writeFileFor(name);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(repaired, file);
        LOGGER.info("[DungeonTrain] Saved building {} to {}", name, file);
        if (!toSource) return;
        Path source = sourceFileFor(name);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Files.createDirectories(source.getParent());
        NbtIo.writeCompressed(repaired, source);
        LOGGER.info("[DungeonTrain] Wrote bundled building {} to {}", name, source);
    }

    /**
     * Delete the player's copies of {@code name} — and, when {@code fromSource}, a new building's source-tree
     * copy (a shipped building's source file is never deleted from here: its structure and pools name it).
     * Returns whether anything was deleted.
     */
    public static boolean deleteFiles(String name, boolean fromSource) throws IOException {
        boolean deleted = false;
        Path player;
        while ((player = playerFile(name)) != null) {
            if (!Files.deleteIfExists(player)) break;
            deleted = true;
        }
        if (fromSource && !isShipped(name)) {
            Path source = sourceFileFor(name);
            if (source != null) deleted |= SourceTreeFiles.deleteWithClasspathTwin(source);
        }
        if (deleted) LOGGER.info("[DungeonTrain] Deleted building {}", name);
        return deleted;
    }

    private static String bundledResource(String name) {
        if (name == null || !Buildings.NAME.matcher(name).matches()) return null;
        if (isShipped(name)) return SHIPPED_RESOURCE + name + EXT;
        String bundledNew = BUNDLED_NEW_RESOURCE + name + EXT;
        return BuildingStore.class.getResource(bundledNew) != null ? bundledNew : null;
    }
}
