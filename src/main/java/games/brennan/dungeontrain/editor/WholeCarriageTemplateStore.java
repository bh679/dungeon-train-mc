package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single-tier template store for whole carriages — {@link WholeCarriage} builds that hold the
 * shell and the interior in one NBT.
 *
 * <p>Deliberately one tier where {@link CarriageTemplateStore} has three. A whole carriage is
 * something a builder made in a builder world; the mod ships none, so there is no bundled resource
 * to fall back to and nothing to promote into the source tree. A missing file simply means the
 * template isn't there, and callers place nothing — the same no-op a custom carriage variant with
 * no saved template already gets.</p>
 *
 * <p>Files live at {@code config/dungeontrain/user/wholecarriages/<id>.nbt}, resolved through
 * {@link UserContentPaths#findFile} so imported packages participate on the same terms as every
 * other store. The distinct subdir is what keeps ids in separate namespaces: a whole carriage and a
 * carriage shell may share a name without either shadowing the other.</p>
 *
 * <p><b>Same name in two stores.</b> The builder's Save writes a whole carriage <em>and</em> the
 * carriage-shell template it has always written, under the same name, because the spawn pool only
 * knows about the latter. Until whole carriages join that pool the two copies stay in step only as
 * long as saves go through {@code BuilderSave}; deleting one leaves the other.</p>
 */
public final class WholeCarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "wholecarriages";
    private static final String EXT = ".nbt";

    /**
     * Cached per-id result. An empty optional means "looked and found nothing", which short-circuits
     * repeat lookups; {@link #clearCache()} is wired into the reload barrier so a package switch
     * can't serve a file from the previous working folder.
     */
    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private WholeCarriageTemplateStore() {}

    /** Active package's write target for this kind. */
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

    /**
     * The saved template for {@code wholeCarriage}, or empty when there is no file for it or the
     * file's footprint doesn't match the world's {@link CarriageDims}.
     */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, WholeCarriage wholeCarriage, CarriageDims dims
    ) {
        String key = wholeCarriage.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached != null) return filterForDims(key, cached, dims);
        Optional<StructureTemplate> loaded = load(level, key, dims);
        CACHE.put(key, loaded);
        return loaded;
    }

    /** Write {@code template} to the active package's copy. */
    public static synchronized void save(WholeCarriage wholeCarriage, StructureTemplate template)
        throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(wholeCarriage);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        CACHE.put(wholeCarriage.id(), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved whole carriage {} to {}", wholeCarriage.id(), file);
    }

    public static synchronized boolean delete(WholeCarriage wholeCarriage) throws IOException {
        Path file = fileFor(wholeCarriage);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(wholeCarriage.id(), Optional.empty());
        if (existed) {
            LOGGER.info("[DungeonTrain] Deleted whole carriage {} ({})", wholeCarriage.id(), file);
        }
        return existed;
    }

    /** True iff a file for {@code wholeCarriage} exists anywhere on the search path. */
    public static boolean exists(WholeCarriage wholeCarriage) {
        return UserContentPaths.findFile(SUBDIR, wholeCarriage.id() + EXT) != null;
    }

    /**
     * Move the active package's file from {@code sourceId} to {@code targetId}. Returns false when
     * there is nothing there to move.
     */
    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed whole-carriage file {} -> {}", src, dst);
        return true;
    }

    private static Optional<StructureTemplate> load(ServerLevel level, String id, CarriageDims dims) {
        Path file = UserContentPaths.findFile(SUBDIR, id + EXT);
        if (file == null) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template.load(blocks, tag);

            Vec3i size = template.getSize();
            if (!CarriagePlacer.sizeMatches(size, dims)) {
                LOGGER.warn(
                    "[DungeonTrain] Whole carriage {} ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                    id, file, size.getX(), size.getY(), size.getZ(),
                    dims.length(), dims.height(), dims.width());
                return Optional.empty();
            }
            LOGGER.info("[DungeonTrain] Loaded whole carriage {} from {}", id, file);
            return Optional.of(template);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read whole carriage {} at {}: {}", id, file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Re-check a cached template against the caller's dims. The cache is filled once per world, but
     * a world whose dims changed mid-session must not be handed a template sized for the old ones.
     */
    private static Optional<StructureTemplate> filterForDims(
        String id, Optional<StructureTemplate> cached, CarriageDims dims
    ) {
        if (cached.isEmpty()) return cached;
        if (CarriagePlacer.sizeMatches(cached.get().getSize(), dims)) return cached;
        LOGGER.warn("[DungeonTrain] Cached whole carriage {} no longer matches dims {}x{}x{} — ignoring.",
            id, dims.length(), dims.width(), dims.height());
        return Optional.empty();
    }

    /**
     * Unified {@link TemplateStore} surface. There is no whole-carriage editor plot, so the
     * player-facing {@code save} arm has nothing to capture — the builder's Save is the only write
     * path, and it calls {@link #save(WholeCarriage, StructureTemplate)} directly. Promotion is
     * unsupported for the same reason {@code Contents} can't promote: no bundled tier exists.
     */
    private static final TemplateStore<Template.WholeCarriage> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.WHOLE_CARRIAGE; }

        @Override
        public SaveResult save(ServerPlayer player, Template.WholeCarriage template) throws Exception {
            throw new UnsupportedOperationException(
                "Whole carriages are saved from the Train Builder, not from an editor plot");
        }

        @Override
        public boolean canPromote(Template.WholeCarriage template) {
            return false;
        }

        @Override
        public void promote(Template.WholeCarriage template) throws Exception {
            throw new UnsupportedOperationException("Whole carriages have no bundled tier to promote into");
        }
    };

    public static TemplateStore<Template.WholeCarriage> adapter() { return ADAPTER; }
}
