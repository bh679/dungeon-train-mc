package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateAdapters;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
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
 * {@code /data/dungeontrain/whole/group/}; dev checkouts promote into the source tree. Policies:
 * {@link SourcePolicy#NEVER}, {@link DeleteCachePolicy#REMOVE}; {@link #exists} asks the whole
 * package search path.</p>
 */
public final class CarriageGroupTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "carriagegroups";
    private static final String EXT = ".nbt";
    public static final String RESOURCE_PREFIX = "/data/dungeontrain/whole/group/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/whole/group";

    private static final ThreeTierStore<String, StructureTemplate> STORE =
        StoreDescriptor.<String, StructureTemplate>named("CarriageGroupTemplateStore")
            .id(id -> id)
            .subdir(SUBDIR)
            .fileName(id -> id + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .codec(StoreCodecs.structureTemplate(false))
            .logNoun("carriage group")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .onSave(id -> ProvenanceCache.invalidateAll())
            .onDelete(id -> ProvenanceCache.invalidateAll())
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .build();

    private CarriageGroupTemplateStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(CarriageGroup group) {
        return fileForId(group.id());
    }

    public static Path fileForId(String id) {
        return STORE.fileFor(id);
    }

    public static void reload() {
        STORE.clearCache();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    /**
     * The saved template for {@code group}, or empty when there is no file, the footprint isn't a
     * whole number of carriages at this world's dims, or that number isn't {@code carriages}
     * ({@code carriages <= 0} accepts any whole number).
     */
    public static Optional<StructureTemplate> get(
        ServerLevel level, CarriageGroup group, CarriageDims dims, int carriages
    ) {
        return filterForShape(group.id(), STORE.get(group.id(), level), dims, carriages);
    }

    /** Bundled tier only, gated like {@link #get}. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, CarriageGroup group, CarriageDims dims, int carriages) {
        return filterForShape(group.id(), STORE.getBundled(group.id(), level), dims, carriages);
    }

    /** How many carriages the saved template holds at {@code dims}, or 0 when it isn't a group. */
    public static int carriagesIn(ServerLevel level, CarriageGroup group, CarriageDims dims) {
        return STORE.get(group.id(), level).map(t -> carriageCount(t.getSize(), dims)).orElse(0);
    }

    /** True iff the mod jar bundles a copy of {@code id}. */
    public static boolean bundled(String id) {
        return STORE.bundled(id);
    }

    /** Write {@code template} to the active package's copy. */
    public static void save(CarriageGroup group, StructureTemplate template) throws IOException {
        STORE.save(group.id(), template);
    }

    public static boolean delete(CarriageGroup group) throws IOException {
        return STORE.delete(group.id());
    }

    /** True iff a file for {@code group} exists anywhere on the user search path. */
    public static boolean exists(CarriageGroup group) {
        return STORE.existsInPackages(group.id());
    }

    public static boolean rename(String sourceId, String targetId) throws IOException {
        return STORE.rename(sourceId, targetId);
    }

    /** The raw NBT, ungated — what the Open grid's 3D preview reads. */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, id + EXT, RESOURCE_PREFIX + id + EXT, "carriage group " + id);
    }

    // ---- source tree (dev mode) -----------------------------------------------------------------

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static Path sourceFileForId(String id) {
        return STORE.sourceFileFor(id);
    }

    public static void saveToSource(CarriageGroup group, StructureTemplate template) throws IOException {
        STORE.saveToSource(group.id(), template);
    }

    public static void promote(CarriageGroup group) throws IOException {
        STORE.promote(group.id());
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

    // ---- unified TemplateStore surface ----------------------------------------------------------

    private static final TemplateStore<Template.CarriageGroup> ADAPTER = TemplateAdapters.of(
        TemplateKind.CARRIAGE_GROUP,
        (player, t) -> WholeCarriageEditor.saveGroup(player, t.group()),
        TemplateAdapters.whenSourceWritable(t -> promote(t.group())));

    public static TemplateStore<Template.CarriageGroup> adapter() { return ADAPTER; }
}
