package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier template store for carriage part NBTs, keyed on
 * {@code (CarriagePartKind, name)}. Mirrors {@link CarriageTemplateStore}'s
 * config/bundled/empty layering but with its own subtree so part NBTs never
 * get scanned as custom carriages (which would fail the 9×7×7 dims check the
 * carriage registry applies — same defensive isolation the tunnel and pillar
 * stores use).
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/parts/<kind>/<name>.nbt}.
 *       Per-install override; the editor writes here via
 *       {@link games.brennan.dungeontrain.editor.CarriagePartEditor#save}.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/parts/<kind>/<name>.nbt}
 *       on the classpath. Optional; shipped defaults only exist for parts the
 *       mod wants to guarantee are available on a fresh install.</li>
 *   <li><b>Empty</b> — missing in both tiers. {@code CarriagePartPlacer.placeAt}
 *       silently skips this stamp (same no-op semantics as the {@code "none"}
 *       sentinel).</li>
 * </ol>
 *
 * <p>Every load is filtered against {@link CarriagePartKind#dims(CarriageDims)}
 * so a template authored at one world's dims can't poison a carriage in a
 * world with different dims — the load returns empty and the stamp is a no-op
 * rather than corrupting the shell.</p>
 */
public final class CarriagePartTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR_BASE = "dungeontrain/parts";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/parts";

    // Key = "<kind>/<name>" — small compound key keeps a single flat map.
    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private CarriagePartTemplateStore() {}

    public static Path directory(CarriagePartKind kind) {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR_BASE).resolve(kind.id());
    }

    public static Path fileFor(CarriagePartKind kind, String name) {
        return directory(kind).resolve(name + EXT);
    }

    public static Path sourceFileFor(CarriagePartKind kind, String name) {
        return sourceDirectory(kind).resolve(name + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims
    ) {
        String key = key(kind, name);
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached != null) return filterForDims(kind, name, cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, kind, name, dims);
        if (loaded.isEmpty()) loaded = loadFromResource(level, kind, name, dims);
        CACHE.put(key, loaded);
        return loaded;
    }

    public static synchronized void save(CarriagePartKind kind, String name, StructureTemplate template) throws IOException {
        Path dir = directory(kind);
        Files.createDirectories(dir);
        Path file = fileFor(kind, name);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        CACHE.put(key(kind, name), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved part template {}:{} to {}", kind.id(), name, file);
    }

    public static synchronized void saveToSource(CarriagePartKind kind, String name, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(kind, name);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        LOGGER.info("[DungeonTrain] Wrote bundled part template {}:{} to {}", kind.id(), name, file);
    }

    public static synchronized void promote(CarriagePartKind kind, String name) throws IOException {
        Path src = fileFor(kind, name);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved part template for " + kind.id() + ":" + name + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(kind, name);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted part template {}:{} from {} to {}", kind.id(), name, src, dst);
    }

    public static synchronized boolean delete(CarriagePartKind kind, String name) throws IOException {
        Path file = fileFor(kind, name);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(key(kind, name), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted part template {}:{} ({})", kind.id(), name, file);
        return existed;
    }

    public static boolean exists(CarriagePartKind kind, String name) {
        return Files.isRegularFile(fileFor(kind, name));
    }

    public static boolean bundled(CarriagePartKind kind, String name) {
        try (InputStream in = CarriagePartTemplateStore.class.getResourceAsStream(
                RESOURCE_PREFIX + kind.id() + "/" + name + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<StructureTemplate> loadFromConfig(
        ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims
    ) {
        Path file = fileFor(kind, name);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, kind, name, dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config part template {}:{} at {}: {}",
                kind.id(), name, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(
        ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims
    ) {
        String resource = RESOURCE_PREFIX + kind.id() + "/" + name + EXT;
        try (InputStream in = CarriagePartTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, kind, name, dims, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled part template {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims,
        CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i expected = kind.dims(dims);
        Vec3i size = template.getSize();
        if (!size.equals(expected)) {
            LOGGER.warn(
                "[DungeonTrain] Part template {}:{} ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                kind.id(), name, origin, size.getX(), size.getY(), size.getZ(),
                expected.getX(), expected.getY(), expected.getZ()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded part template {}:{} from {}", kind.id(), name, origin);
        return Optional.of(template);
    }

    private static Optional<StructureTemplate> filterForDims(
        CarriagePartKind kind, String name, Optional<StructureTemplate> cached, CarriageDims dims
    ) {
        if (cached.isEmpty()) return cached;
        Vec3i expected = kind.dims(dims);
        if (cached.get().getSize().equals(expected)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached part template {}:{} no longer matches dims {}x{}x{} — falling back.",
            kind.id(), name, dims.length(), dims.width(), dims.height()
        );
        return Optional.empty();
    }

    private static String key(CarriagePartKind kind, String name) {
        return kind.id() + "/" + name;
    }

    private static Path sourceDirectory(CarriagePartKind kind) {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(kind.id());
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

    /**
     * Phase-2 adapter — exposes part save/promote through the unified
     * {@link TemplateStore} surface. Cached per
     * {@link CarriagePartKind} because each kind has its own physical
     * {@code (length × width × height)} footprint and the
     * {@link Template.Part#partKind()} is the discriminator the
     * underlying static methods key on.
     */
    private static final EnumMap<CarriagePartKind, TemplateStore<Template.Part>> ADAPTERS
        = new EnumMap<>(CarriagePartKind.class);
    static {
        for (CarriagePartKind k : CarriagePartKind.values()) ADAPTERS.put(k, makeAdapter(k));
    }

    private static TemplateStore<Template.Part> makeAdapter(CarriagePartKind kind) {
        return new TemplateStore<>() {
            @Override public TemplateKind kind() { return TemplateKind.PART; }

            @Override
            public SaveResult save(ServerPlayer player, Template.Part template) throws Exception {
                CarriagePartEditor.SaveResult r = CarriagePartEditor.save(player, kind, template.name());
                return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
            }

            @Override
            public boolean canPromote(Template.Part template) {
                return sourceTreeAvailable() && exists(kind, template.name());
            }

            @Override
            public void promote(Template.Part template) throws Exception {
                CarriagePartTemplateStore.promote(kind, template.name());
            }
        };
    }

    public static TemplateStore<Template.Part> adapter(CarriagePartKind kind) {
        return ADAPTERS.get(kind);
    }

    /**
     * Phase-3 record-shaped overload: {@link #adapter(CarriagePartKind)}
     * keyed via the {@link games.brennan.dungeontrain.template.CarriagePartTemplateId}
     * record. The underlying EnumMap cache key stays the bare
     * {@link CarriagePartKind}; the id record is a callsite shape only.
     */
    public static TemplateStore<Template.Part> adapter(games.brennan.dungeontrain.template.CarriagePartTemplateId id) {
        return ADAPTERS.get(id.kind());
    }
}
