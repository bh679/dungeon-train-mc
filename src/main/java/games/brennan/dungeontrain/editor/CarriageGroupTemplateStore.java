package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
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
 * Three-tier template store for {@link CarriageGroup} builds — a whole run of carriages in one NBT,
 * the Whole section's <b>Group</b> kind.
 *
 * <p>Modelled on {@link WholeCarriageTemplateStore}, and differing in exactly one thing that matters:
 * the footprint gate. A whole carriage is refused unless it is exactly one {@link CarriageDims}; a
 * group is refused unless it is a <em>whole number</em> of them and that number is the one the caller
 * is asking for. That gate is also how the carriage count is stored: it is the footprint, not a
 * sidecar, so it cannot drift from the blocks. A group authored at three carriages is never offered
 * to a world whose group is two or four.</p>
 *
 * <p>Tiers: {@code config/dungeontrain/user/carriagegroups/} (+ imports), then bundled
 * {@code /data/dungeontrain/whole/group/}; dev checkouts promote into the source tree.</p>
 */
public final class CarriageGroupTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "carriagegroups";
    private static final String EXT = ".nbt";
    public static final String RESOURCE_PREFIX = "/data/dungeontrain/whole/group/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/whole/group";

    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private CarriageGroupTemplateStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(CarriageGroup group) {
        return fileForId(group.id());
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

    /**
     * The saved template for {@code group}, or empty when there is no file, the footprint isn't a
     * whole number of carriages at this world's dims, or that number isn't {@code carriages}
     * ({@code carriages <= 0} accepts any whole number).
     */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, CarriageGroup group, CarriageDims dims, int carriages
    ) {
        return filterForShape(group.id(), resolve(level, group.id()), dims, carriages);
    }

    /** Bundled tier only, gated like {@link #get}. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, CarriageGroup group, CarriageDims dims, int carriages) {
        return filterForShape(group.id(), loadFromResource(level, group.id()), dims, carriages);
    }

    /** How many carriages the saved template holds at {@code dims}, or 0 when it isn't a group. */
    public static synchronized int carriagesIn(ServerLevel level, CarriageGroup group, CarriageDims dims) {
        return resolve(level, group.id()).map(t -> carriageCount(t.getSize(), dims)).orElse(0);
    }

    private static Optional<StructureTemplate> resolve(ServerLevel level, String key) {
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached == null) {
            cached = loadFromConfig(level, key);
            if (cached.isEmpty()) cached = loadFromResource(level, key);
            CACHE.put(key, cached);
        }
        return cached;
    }

    /** True iff the mod jar bundles a copy of {@code id}. */
    public static boolean bundled(String id) {
        try (InputStream in = CarriageGroupTemplateStore.class.getResourceAsStream(RESOURCE_PREFIX + id + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Write {@code template} to the active package's copy. */
    public static synchronized void save(CarriageGroup group, StructureTemplate template)
        throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(group);
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
        CACHE.put(group.id(), Optional.of(template));
        ProvenanceCache.invalidateAll();
        LOGGER.info("[DungeonTrain] Saved carriage group {} to {}", group.id(), file);
    }

    public static synchronized boolean delete(CarriageGroup group) throws IOException {
        Path file = fileFor(group);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(group.id());
        ProvenanceCache.invalidateAll();
        if (existed) LOGGER.info("[DungeonTrain] Deleted carriage group {} ({})", group.id(), file);
        return existed;
    }

    /** True iff a file for {@code group} exists anywhere on the user search path. */
    public static boolean exists(CarriageGroup group) {
        return UserContentPaths.findFile(SUBDIR, group.id() + EXT) != null;
    }

    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        ProvenanceCache.invalidateAll();
        LOGGER.info("[DungeonTrain] Renamed carriage-group file {} -> {}", src, dst);
        return true;
    }

    /** The raw NBT, ungated — what the Open grid's 3D preview reads. */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, id + EXT, RESOURCE_PREFIX + id + EXT, "carriage group " + id);
    }

    // ---- source tree (dev mode) -----------------------------------------------------------------

    public static boolean sourceTreeAvailable() {
        return CarriageTemplateStore.sourceTreeAvailable();
    }

    public static Path sourceFileForId(String id) {
        return CarriageTemplateStore.projectRoot().resolve(SOURCE_REL_PATH).resolve(id + EXT);
    }

    public static synchronized void saveToSource(CarriageGroup group, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileForId(group.id());
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
        LOGGER.info("[DungeonTrain] Wrote bundled carriage group {} to {}", group.id(), file);
    }

    public static synchronized void promote(CarriageGroup group) throws IOException {
        Path src = fileFor(group);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved carriage group for " + group.id() + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileForId(group.id());
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted carriage group {} from {} to {}", group.id(), src, dst);
    }

    // ---- shape ----------------------------------------------------------------------------------

    /**
     * How many carriages a footprint holds, or 0 when it isn't a run of them: height and width must
     * match exactly and the length must divide evenly.
     */
    public static int carriageCount(Vec3i size, CarriageDims dims) {
        if (size == null || dims == null || dims.length() <= 0) return 0;
        if (size.getY() != dims.height() || size.getZ() != dims.width()) return 0;
        if (size.getX() <= 0 || size.getX() % dims.length() != 0) return 0;
        return size.getX() / dims.length();
    }

    private static Optional<StructureTemplate> filterForShape(
        String id, Optional<StructureTemplate> loaded, CarriageDims dims, int carriages
    ) {
        if (loaded.isEmpty()) return loaded;
        Vec3i size = loaded.get().getSize();
        int held = carriageCount(size, dims);
        if (held == 0) {
            LOGGER.warn("[DungeonTrain] Carriage group {} has bounds {}x{}x{}, not a run of {}x{}x{} carriages — ignoring.",
                id, size.getX(), size.getY(), size.getZ(), dims.length(), dims.height(), dims.width());
            return Optional.empty();
        }
        if (carriages > 0 && held != carriages) {
            LOGGER.warn("[DungeonTrain] Carriage group {} holds {} carriage(s); this train's group is {} — ignoring.",
                id, held, carriages);
            return Optional.empty();
        }
        return loaded;
    }

    // ---- loaders --------------------------------------------------------------------------------

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, String id) {
        Path file = UserContentPaths.findFile(SUBDIR, id + EXT);
        if (file == null) return Optional.empty();
        try {
            return load(level, id, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read carriage group {} at {}: {}", id, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, String id) {
        String resource = RESOURCE_PREFIX + id + EXT;
        try (InputStream in = CarriageGroupTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            return load(level, id, NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled carriage group {} at {}: {}", id, resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> load(ServerLevel level, String id, CompoundTag tag, String origin) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);
        LOGGER.info("[DungeonTrain] Loaded carriage group {} from {}", id, origin);
        return Optional.of(template);
    }

    // ---- unified TemplateStore surface ----------------------------------------------------------

    private static final TemplateStore<Template.CarriageGroup> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.CARRIAGE_GROUP; }

        @Override
        public SaveResult save(ServerPlayer player, Template.CarriageGroup template) throws Exception {
            return WholeCarriageEditor.saveGroup(player, template.group());
        }

        @Override
        public boolean canPromote(Template.CarriageGroup template) {
            return sourceTreeAvailable();
        }

        @Override
        public void promote(Template.CarriageGroup template) throws Exception {
            CarriageGroupTemplateStore.promote(template.group());
        }
    };

    public static TemplateStore<Template.CarriageGroup> adapter() { return ADAPTER; }
}
