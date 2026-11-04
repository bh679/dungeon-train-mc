package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateAdapters;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
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
 * <p>A typed face over a {@link ThreeTierStore}. Policies: {@link SourcePolicy#NEVER} (the editor
 * promotes explicitly), {@link DeleteCachePolicy#REMOVE}. <b>The cache holds what is on disk; the
 * dims question is answered per call</b> — see {@link CarriageTemplateStore#get} for the poisoning
 * bug that rule closed. {@link #exists} asks the whole package search path.</p>
 */
public final class WholeCarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "wholecarriages";
    private static final String EXT = ".nbt";
    public static final String RESOURCE_PREFIX = "/data/dungeontrain/whole/room/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/whole/room";

    private static final ThreeTierStore<String, StructureTemplate> STORE =
        StoreDescriptor.<String, StructureTemplate>named("WholeCarriageTemplateStore")
            .id(id -> id)
            .subdir(SUBDIR)
            .fileName(id -> id + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .codec(StoreCodecs.structureTemplate(false))
            .logNoun("whole carriage")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .onSave(id -> ProvenanceCache.invalidateAll())
            .onDelete(id -> ProvenanceCache.invalidateAll())
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .build();

    private WholeCarriageTemplateStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(WholeCarriage wholeCarriage) {
        return fileForId(wholeCarriage.id());
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

    /** The template for {@code wholeCarriage} from config or bundled, gated to {@code dims}. */
    public static Optional<StructureTemplate> get(
        ServerLevel level, WholeCarriage wholeCarriage, CarriageDims dims
    ) {
        return filterForDims(wholeCarriage.id(), STORE.get(wholeCarriage.id(), level), dims);
    }

    /** Bundled tier only — what {@code /dt reset default} re-stamps from. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, WholeCarriage wholeCarriage, CarriageDims dims) {
        return filterForDims(wholeCarriage.id(), STORE.getBundled(wholeCarriage.id(), level), dims);
    }

    /** True iff the mod jar bundles a copy of {@code id}. */
    public static boolean bundled(String id) {
        return STORE.bundled(id);
    }

    /** The raw NBT, ungated — what tile previews read. */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, id + EXT, RESOURCE_PREFIX + id + EXT, "whole carriage " + id);
    }

    /** Write {@code template} to the active package's copy. */
    public static void save(WholeCarriage wholeCarriage, StructureTemplate template) throws IOException {
        STORE.save(wholeCarriage.id(), template);
    }

    public static boolean delete(WholeCarriage wholeCarriage) throws IOException {
        return STORE.delete(wholeCarriage.id());
    }

    /** True iff a file for {@code wholeCarriage} exists anywhere on the user search path. */
    public static boolean exists(WholeCarriage wholeCarriage) {
        return STORE.existsInPackages(wholeCarriage.id());
    }

    public static boolean rename(String sourceId, String targetId) throws IOException {
        return STORE.rename(sourceId, targetId);
    }

    // ---- source tree (dev mode) -----------------------------------------------------------------

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static Path sourceFileForId(String id) {
        return STORE.sourceFileFor(id);
    }

    /** Write {@code template} straight into the source tree so it ships with the next build. */
    public static void saveToSource(WholeCarriage wholeCarriage, StructureTemplate template) throws IOException {
        STORE.saveToSource(wholeCarriage.id(), template);
    }

    /** Copy the config copy into the source tree. */
    public static void promote(WholeCarriage wholeCarriage) throws IOException {
        STORE.promote(wholeCarriage.id());
    }

    // ---- gate -----------------------------------------------------------------------------------

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

    private static final TemplateStore<Template.WholeCarriage> ADAPTER = TemplateAdapters.of(
        TemplateKind.WHOLE_CARRIAGE,
        (player, t) -> WholeCarriageEditor.saveRoom(player, t.wholeCarriage()),
        TemplateAdapters.whenSourceWritable(t -> promote(t.wholeCarriage())));

    public static TemplateStore<Template.WholeCarriage> adapter() { return ADAPTER; }

    /** The kind this store backs, for callers that route by {@link WholeKind}. */
    public static WholeKind wholeKind() { return WholeKind.ROOM; }
}
