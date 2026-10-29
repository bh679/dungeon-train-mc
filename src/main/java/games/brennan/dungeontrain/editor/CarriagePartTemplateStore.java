package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.CarriagePartTemplateId;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateAdapters;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
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
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/parts/<kind>/<name>.nbt}.
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
 * <p>Every read is filtered against {@link CarriagePartKind#dims(CarriageDims)}
 * so a template authored at one world's dims can't poison a carriage in a
 * world with different dims — the read returns empty and the stamp is a no-op
 * rather than corrupting the shell. The gate is applied <i>per call</i>, outside the cache: a
 * rejection at one caller's dims used to be cached as "absent" for every later caller.</p>
 *
 * <p>Policies: {@link SourcePolicy#NEVER}, {@link DeleteCachePolicy#REMOVE}, cache entry dropped
 * on save (the file is the repaired copy). {@link #exists} asks the active package only.</p>
 */
public final class CarriagePartTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR_BASE = "parts";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/parts";

    /** A part's address. */
    private record Key(CarriagePartKind kind, String name) {
        String id() {
            return kind.id() + "/" + name;
        }
    }

    private static final ThreeTierStore<Key, StructureTemplate> STORE =
        StoreDescriptor.<Key, StructureTemplate>named("CarriagePartTemplateStore")
            .id(Key::id)
            .subdir(k -> SUBDIR_BASE + "/" + k.kind().id())
            .fileName(k -> k.name() + EXT)
            .bundledResource(k -> RESOURCE_PREFIX + k.kind().id() + "/" + k.name() + EXT)
            .sourceRelDir(k -> SOURCE_REL_PATH + "/" + k.kind().id())
            .codec(StoreCodecs.structureTemplate(true))
            .logNoun("part template")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .saveCachePolicy(StoreDescriptor.SaveCachePolicy.DROP)
            .onSave(k -> {
                StageBlockIndex.invalidateAll();
                ProvenanceCache.invalidateAll();
            })
            .onDelete(k -> {
                StageBlockIndex.invalidateAll();
                ProvenanceCache.invalidateAll();
            })
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .onClear(StageBlockIndex::invalidateAll)
            .build();

    private CarriagePartTemplateStore() {}

    public static Path directory(CarriagePartKind kind) {
        return UserContentPaths.dir(SUBDIR_BASE).resolve(kind.id());
    }

    public static Path fileFor(CarriagePartKind kind, String name) {
        return STORE.fileFor(new Key(kind, name));
    }

    public static Path sourceFileFor(CarriagePartKind kind, String name) {
        return STORE.sourceFileFor(new Key(kind, name));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    /**
     * This part's raw NBT, with no dims gate — for showing the template rather than stamping it.
     * See {@link TemplateNbt} for why the gate is skipped.
     */
    public static Optional<CompoundTag> rawTag(CarriagePartKind kind, String name) {
        return TemplateNbt.read(SUBDIR_BASE + "/" + kind.id(), name + EXT,
            RESOURCE_PREFIX + kind.id() + "/" + name + EXT, "part template " + kind.id() + ":" + name);
    }

    public static Optional<StructureTemplate> get(
        ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims
    ) {
        return filterForDims(kind, name, STORE.get(new Key(kind, name), level), dims);
    }

    public static void save(CarriagePartKind kind, String name, StructureTemplate template) throws IOException {
        STORE.save(new Key(kind, name), template);
    }

    public static void saveToSource(CarriagePartKind kind, String name, StructureTemplate template) throws IOException {
        STORE.saveToSource(new Key(kind, name), template);
    }

    public static void promote(CarriagePartKind kind, String name) throws IOException {
        STORE.promote(new Key(kind, name));
        try {
            ContainerContentsStore.promoteFor(BlockVariantPlot.partKey(kind, name));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Promoted part template {}:{} but failed to promote container sidecar: {}",
                kind.id(), name, e.toString());
        }
    }

    public static boolean delete(CarriagePartKind kind, String name) throws IOException {
        return STORE.delete(new Key(kind, name));
    }

    /**
     * Move a saved part template to a new name within its kind, keeping its cache entry with it.
     *
     * <p>Within its kind, because a part id is only unique there — {@code standard} is both a floor
     * and a door, so a rename that crossed kinds would collide with a different part entirely. The
     * file half of a rename only; the caller re-registers the name.</p>
     */
    public static boolean rename(CarriagePartKind kind, String sourceName, String targetName)
            throws IOException {
        return STORE.rename(new Key(kind, sourceName), new Key(kind, targetName));
    }

    public static boolean exists(CarriagePartKind kind, String name) {
        return STORE.existsInActive(new Key(kind, name));
    }

    public static boolean bundled(CarriagePartKind kind, String name) {
        return STORE.bundled(new Key(kind, name));
    }

    /** The size gate — per call, never cached. */
    private static Optional<StructureTemplate> filterForDims(
        CarriagePartKind kind, String name, Optional<StructureTemplate> loaded, CarriageDims dims
    ) {
        if (loaded.isEmpty()) return loaded;
        Vec3i expected = kind.dims(dims);
        Vec3i size = loaded.get().getSize();
        if (size.equals(expected)) return loaded;
        LOGGER.warn(
            "[DungeonTrain] Part template {}:{} has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
            kind.id(), name, size.getX(), size.getY(), size.getZ(),
            expected.getX(), expected.getY(), expected.getZ()
        );
        return Optional.empty();
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
        return TemplateAdapters.of(TemplateKind.PART,
            (player, t) -> CarriagePartEditor.save(player, new CarriagePartTemplateId(kind, t.name())),
            TemplateAdapters.when(
                t -> sourceTreeAvailable() && exists(kind, t.name()),
                t -> promote(kind, t.name())));
    }

    public static TemplateStore<Template.Part> adapter(CarriagePartKind kind) {
        return ADAPTERS.get(kind);
    }

    /**
     * Phase-3 record-shaped overload: {@link #adapter(CarriagePartKind)}
     * keyed via the {@link CarriagePartTemplateId}
     * record. The underlying EnumMap cache key stays the bare
     * {@link CarriagePartKind}; the id record is a callsite shape only.
     */
    public static TemplateStore<Template.Part> adapter(CarriagePartTemplateId id) {
        return ADAPTERS.get(id.kind());
    }
}
