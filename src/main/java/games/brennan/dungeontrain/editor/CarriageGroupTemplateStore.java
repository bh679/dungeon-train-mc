package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Template store for {@link CarriageGroup} builds — a whole run of carriages in one NBT.
 *
 * <p>Modelled on {@link WholeCarriageTemplateStore}, and differing in exactly one thing that matters:
 * the footprint gate. A whole carriage is refused unless it is exactly one {@link CarriageDims}; a
 * group is refused unless it is a <em>whole number</em> of them and that number is the one the caller
 * is asking for. Sharing a store would make one of those two checks impossible to state.</p>
 *
 * <p>That gate is also how the carriage count is stored: it is the footprint, not a sidecar, so it
 * cannot drift from the blocks. A group authored at three carriages is refused by a world whose group
 * is two or four rather than being stamped at the wrong length — the same posture the dims gate takes
 * for a carriage authored at other dimensions.</p>
 *
 * <p>Files live at {@code config/dungeontrain/user/carriagegroups/<id>.nbt}, resolved through
 * {@link UserContentPaths#findFile} so imported packages participate on the same terms as every other
 * store. The distinct subdir keeps the id namespaces apart: a group and a carriage may share a name
 * without either shadowing the other.</p>
 *
 * <p><b>Not a spawn pool.</b> Groups are authored and reopened by the Train Builder and uploaded to
 * their author's relay profile; nothing places one on a train yet. That is why the relay's lease is
 * hard-filtered to carriages — see {@code BuilderRelayKinds}.</p>
 */
public final class CarriageGroupTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "carriagegroups";
    private static final String EXT = ".nbt";

    /** Cached per-id, like every other store. Empty means "looked and found nothing". */
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
     * whole number of carriages at this world's dims, or that number isn't {@code carriages}.
     */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, CarriageGroup group, CarriageDims dims, int carriages
    ) {
        String key = group.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        Optional<StructureTemplate> resolved = cached != null ? cached : load(level, key);
        if (cached == null) CACHE.put(key, resolved);
        return filterForShape(key, resolved, dims, carriages);
    }

    /** How many carriages the saved template holds at {@code dims}, or 0 when it isn't a group. */
    public static synchronized int carriagesIn(ServerLevel level, CarriageGroup group, CarriageDims dims) {
        String key = group.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        Optional<StructureTemplate> resolved = cached != null ? cached : load(level, key);
        if (cached == null) CACHE.put(key, resolved);
        return resolved.map(t -> carriageCount(t.getSize(), dims)).orElse(0);
    }

    /** Write {@code template} to the active package's copy. */
    public static synchronized void save(CarriageGroup group, StructureTemplate template)
        throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(group);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        CACHE.put(group.id(), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved carriage group {} to {}", group.id(), file);
    }

    public static synchronized boolean delete(CarriageGroup group) throws IOException {
        Path file = fileFor(group);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(group.id(), Optional.empty());
        if (existed) {
            LOGGER.info("[DungeonTrain] Deleted carriage group {} ({})", group.id(), file);
        }
        return existed;
    }

    /** True iff a file for {@code group} exists anywhere on the search path. */
    public static boolean exists(CarriageGroup group) {
        return UserContentPaths.findFile(SUBDIR, group.id() + EXT) != null;
    }

    /** Move the active package's file. False when there is nothing there to move. */
    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed carriage-group file {} -> {}", src, dst);
        return true;
    }

    /**
     * The raw NBT, ungated — what the Open grid's 3D preview reads.
     *
     * <p>Ungated on purpose, exactly as the other stores' {@code rawTag} helpers are: the dims filter
     * is right for stamping and wrong for looking, since it would blank out precisely the odd-sized
     * groups a builder is most likely to be hunting through.</p>
     */
    public static Optional<CompoundTag> rawTag(String id) {
        Path file = UserContentPaths.findFile(SUBDIR, id + EXT);
        if (file == null) return Optional.empty();
        try {
            return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not read carriage group {} at {}: {}", id, file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * How many carriages a footprint holds, or 0 when it isn't a run of them.
     *
     * <p>Height and width must match exactly and the length must divide evenly: a template one block
     * short of two carriages is not "nearly two", it is a template that would be stamped into the
     * wrong space.</p>
     */
    public static int carriageCount(Vec3i size, CarriageDims dims) {
        if (size == null || dims == null || dims.length() <= 0) return 0;
        if (size.getY() != dims.height() || size.getZ() != dims.width()) return 0;
        if (size.getX() <= 0 || size.getX() % dims.length() != 0) return 0;
        return size.getX() / dims.length();
    }

    private static Optional<StructureTemplate> load(ServerLevel level, String id) {
        Path file = UserContentPaths.findFile(SUBDIR, id + EXT);
        if (file == null) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template.load(blocks, tag);
            LOGGER.info("[DungeonTrain] Loaded carriage group {} from {}", id, file);
            return Optional.of(template);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read carriage group {} at {}: {}", id, file, e.toString());
            return Optional.empty();
        }
    }

    /** The footprint gate, applied on every read so a mid-session dims change can't slip through. */
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
}
