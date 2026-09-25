package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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
 * Three-tier template store for whole carriages — {@link WholeCarriage} builds that hold the shell
 * and the interior in one NBT, the Whole section's <b>Room</b> kind.
 *
 * <p>Tiers, in lookup order: the active package's {@code config/dungeontrain/user/wholecarriages/}
 * (and every enabled import, via {@link UserContentPaths#findFile}), then the bundled
 * {@code /data/dungeontrain/whole/room/} on the classpath. A dev checkout can promote a config copy
 * into the source tree so it ships with the next build, exactly as {@link CarriageTemplateStore}
 * does. The user subdirectory keeps its pre-section name so existing installs and relay slugs keep
 * working.</p>
 *
 * <p><b>The cache holds what is on disk; the dims question is answered per call.</b> See
 * {@link CarriageTemplateStore#get} for the poisoning bug that rule closed.</p>
 */
public final class WholeCarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "wholecarriages";
    private static final String EXT = ".nbt";
    public static final String RESOURCE_PREFIX = "/data/dungeontrain/whole/room/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/whole/room";

    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private WholeCarriageTemplateStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(WholeCarriage wholeCarriage) {
        return fileForId(wholeCarriage.id());
    }

    public static Path fileForId(String id) {
        return directory().resolve(id + EXT);
    }

    public static synchronized void reload() {
        CACHE.clear();
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** The template for {@code wholeCarriage} from config or bundled, gated to {@code dims}. */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, WholeCarriage wholeCarriage, CarriageDims dims
    ) {
        String key = wholeCarriage.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached == null) {
            cached = loadFromConfig(level, key);
            if (cached.isEmpty()) cached = loadFromResource(level, key);
            CACHE.put(key, cached);
        }
        return filterForDims(key, cached, dims);
    }

    /** Bundled tier only — what {@code /dt reset default} re-stamps from. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, WholeCarriage wholeCarriage, CarriageDims dims) {
        return filterForDims(wholeCarriage.id(), loadFromResource(level, wholeCarriage.id()), dims);
    }

    /** True iff the mod jar bundles a copy of {@code id}. */
    public static boolean bundled(String id) {
        try (InputStream in = WholeCarriageTemplateStore.class.getResourceAsStream(RESOURCE_PREFIX + id + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The raw NBT, ungated — what tile previews read. */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, id + EXT, RESOURCE_PREFIX + id + EXT, "whole carriage " + id);
    }

    /** Write {@code template} to the active package's copy. */
    public static synchronized void save(WholeCarriage wholeCarriage, StructureTemplate template)
        throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(wholeCarriage);
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
        CACHE.put(wholeCarriage.id(), Optional.of(template));
        ProvenanceCache.invalidateAll();
        LOGGER.info("[DungeonTrain] Saved whole carriage {} to {}", wholeCarriage.id(), file);
    }

    public static synchronized boolean delete(WholeCarriage wholeCarriage) throws IOException {
        Path file = fileFor(wholeCarriage);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(wholeCarriage.id());
        ProvenanceCache.invalidateAll();
        if (existed) LOGGER.info("[DungeonTrain] Deleted whole carriage {} ({})", wholeCarriage.id(), file);
        return existed;
    }

    /** True iff a file for {@code wholeCarriage} exists anywhere on the user search path. */
    public static boolean exists(WholeCarriage wholeCarriage) {
        return UserContentPaths.findFile(SUBDIR, wholeCarriage.id() + EXT) != null;
    }

    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        ProvenanceCache.invalidateAll();
        LOGGER.info("[DungeonTrain] Renamed whole-carriage file {} -> {}", src, dst);
        return true;
    }

    // ---- source tree (dev mode) -----------------------------------------------------------------

    public static boolean sourceTreeAvailable() {
        return CarriageTemplateStore.sourceTreeAvailable();
    }

    public static Path sourceFileForId(String id) {
        return CarriageTemplateStore.projectRoot().resolve(SOURCE_REL_PATH).resolve(id + EXT);
    }

    /** Write {@code template} straight into the source tree so it ships with the next build. */
    public static synchronized void saveToSource(WholeCarriage wholeCarriage, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileForId(wholeCarriage.id());
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
        LOGGER.info("[DungeonTrain] Wrote bundled whole carriage {} to {}", wholeCarriage.id(), file);
    }

    /** Copy the config copy into the source tree. */
    public static synchronized void promote(WholeCarriage wholeCarriage) throws IOException {
        Path src = fileFor(wholeCarriage);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved whole carriage for " + wholeCarriage.id() + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileForId(wholeCarriage.id());
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted whole carriage {} from {} to {}", wholeCarriage.id(), src, dst);
    }

    // ---- loaders --------------------------------------------------------------------------------

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, String id) {
        Path file = UserContentPaths.findFile(SUBDIR, id + EXT);
        if (file == null) return Optional.empty();
        try {
            return load(level, id, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read whole carriage {} at {}: {}", id, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, String id) {
        String resource = RESOURCE_PREFIX + id + EXT;
        try (InputStream in = WholeCarriageTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            return load(level, id, NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled whole carriage {} at {}: {}", id, resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> load(ServerLevel level, String id, CompoundTag tag, String origin) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, DoubleBlockTemplateRepair.repair(tag, id));
        LOGGER.info("[DungeonTrain] Loaded whole carriage {} from {}", id, origin);
        return Optional.of(template);
    }

    private static Optional<StructureTemplate> filterForDims(
        String id, Optional<StructureTemplate> loaded, CarriageDims dims
    ) {
        if (loaded.isEmpty()) return loaded;
        Vec3i size = loaded.get().getSize();
        if (CarriagePlacer.sizeMatches(size, dims)) return loaded;
        LOGGER.warn("[DungeonTrain] Whole carriage {} has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
            id, size.getX(), size.getY(), size.getZ(), dims.length(), dims.height(), dims.width());
        return Optional.empty();
    }

    // ---- unified TemplateStore surface ----------------------------------------------------------

    private static final TemplateStore<Template.WholeCarriage> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.WHOLE_CARRIAGE; }

        @Override
        public SaveResult save(ServerPlayer player, Template.WholeCarriage template) throws Exception {
            return WholeCarriageEditor.saveRoom(player, template.wholeCarriage());
        }

        @Override
        public boolean canPromote(Template.WholeCarriage template) {
            return sourceTreeAvailable();
        }

        @Override
        public void promote(Template.WholeCarriage template) throws Exception {
            WholeCarriageTemplateStore.promote(template.wholeCarriage());
        }
    };

    public static TemplateStore<Template.WholeCarriage> adapter() { return ADAPTER; }

    /** The kind this store backs, for callers that route by {@link WholeKind}. */
    public static WholeKind wholeKind() { return WholeKind.ROOM; }
}
