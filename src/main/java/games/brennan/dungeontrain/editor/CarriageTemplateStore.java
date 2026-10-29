package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateAdapters;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.ShellPool;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier carriage template store, keyed on {@link CarriageVariant}.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/templates/<id>.nbt}, the
 *       per-install override. Server admins / single-player customisations land here.
 *       Custom variants only exist at this tier — they have no bundled resource.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.nbt} on the
 *       classpath. Ships inside the mod jar and represents the mod's defaults.
 *       Only built-ins have bundled copies.</li>
 *   <li><b>Hardcoded fallback</b> — {@link CarriagePlacer#placeAt} drops to its
 *       legacy generator when both above tiers miss. Only built-ins fall back;
 *       custom variants with no config-dir file place nothing.</li>
 * </ol>
 *
 * Each tier is filtered against the caller's {@link CarriageDims}; templates
 * whose recorded footprint doesn't match the world's dims are skipped so the
 * next tier (or the hardcoded generator) gets a turn.
 *
 * Authors push edits from the editor to tier 2 either by toggling dev mode
 * ({@link EditorDevMode}) so {@link CarriageEditor#save} writes through to the
 * source tree, or by running {@code /dungeontrain editor promote}. Both paths
 * only apply to built-ins.
 *
 * <p>A typed face over a {@link ThreeTierStore}. Policies: {@link SourcePolicy#NEVER} (the editor
 * promotes explicitly, above), {@link DeleteCachePolicy#REMOVE} (deleting a built-in's override
 * resolves to the bundled copy at once), and the cache entry is <i>dropped</i> on save because the
 * file on disk is the {@link DoubleBlockTemplateRepair repaired} copy. {@link #exists} asks the
 * active package only — it feeds the overwrite prompt and {@code canPromote}.</p>
 */
public final class CarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "templates";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    /** {@link #hasBlocks} answers, per id; dropped with every template change. */
    private static final Map<String, Boolean> HAS_BLOCKS = new HashMap<>();

    private static final ThreeTierStore<String, StructureTemplate> STORE =
        StoreDescriptor.<String, StructureTemplate>named("CarriageTemplateStore")
            .id(id -> id)
            .subdir(SUBDIR)
            .fileName(id -> ShellPool.path(id) + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .codec(StoreCodecs.structureTemplate(true))
            .logNoun("template")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .saveCachePolicy(StoreDescriptor.SaveCachePolicy.DROP)
            .onSave(id -> {
                forgetBlocks(id);
                ProvenanceCache.invalidateAll();
                // A flatbed's pads are cut from its template — the next pad re-cuts from the saved one.
                CarriagePlacer.clearHalfFlatbedCache();
            })
            .onDelete(id -> {
                forgetBlocks(id);
                ProvenanceCache.invalidateAll();
            })
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .onClear(CarriageTemplateStore::forgetAllBlocks)
            .build();

    private CarriageTemplateStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(CarriageVariant variant) {
        return STORE.fileFor(variant.id());
    }

    public static Path fileForId(String id) {
        return STORE.fileFor(id);
    }

    /**
     * This carriage template's raw NBT, with no dims gate — for showing the template rather than
     * stamping it. See {@link TemplateNbt} for why the gate is skipped.
     */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, ShellPool.path(id) + EXT, RESOURCE_PREFIX + ShellPool.path(id) + EXT,
            "carriage template " + id);
    }

    /**
     * On-disk path to the bundled-resource copy of {@code type}'s template
     * inside the project source tree. Only meaningful in a dev checkout — see
     * {@link #sourceTreeAvailable()}. Only built-ins ship bundled copies.
     */
    public static Path sourceFileFor(CarriageType type) {
        return STORE.sourceFileFor(CarriageVariant.of(type).id());
    }

    /**
     * True iff the project source tree is on disk and writable. Distinguishes
     * a {@code ./gradlew runClient} dev session (where {@code FMLPaths.GAMEDIR}
     * resolves to {@code <project>/run}) from a packaged jar install (where
     * the source tree does not exist alongside the game directory). See {@link SourceTree}.
     */
    public static boolean sourceTreeAvailable() {
        return SourceTree.available();
    }

    public static void reload() {
        STORE.clearCache();
    }

    /**
     * Invalidate the whole cache. Wired to {@code ServerStoppedEvent} so a
     * switch between two single-player worlds with different
     * {@link CarriageDims} cannot accidentally return a stale template
     * saved against the previous world's dims.
     */
    public static void clearCache() {
        STORE.clearCache();
    }

    private static synchronized void forgetBlocks(String id) {
        HAS_BLOCKS.remove(id);
    }

    private static synchronized void forgetAllBlocks() {
        HAS_BLOCKS.clear();
    }

    /**
     * Whether {@code id}'s template holds any blocks. A carriage made as a blank and never built is
     * a box of air — stood around contents, or put on the train, it is no carriage at all — so the
     * shell choices skip it. A variant with no template file at all has built-in geometry and counts
     * as built.
     */
    public static synchronized boolean hasBlocks(String id) {
        return HAS_BLOCKS.computeIfAbsent(id, k -> rawTag(k)
            .map(tag -> !tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty())
            .orElse(true));
    }

    /**
     * The template for {@code variant}, if one exists <b>and</b> it is the size {@code dims}
     * describes.
     *
     * <p><b>The cache holds what is on disk; the dims question is answered per call.</b> The cache is
     * keyed by variant id alone, so it must not record one caller's dims verdict. It used to: a
     * lookup at the wrong dims failed the size gate during the load, and the resulting
     * {@code Optional.empty()} was cached under that id — which then defeated every later lookup at
     * the <i>right</i> dims for the rest of the session. That is not a hypothetical. The portal
     * corridor's template is deliberately longer than a carriage
     * ({@code PortalCorridorSize}), and {@code CarriagePlacer.warmTemplateCaches} sweeps every
     * registered variant at plain carriage dims on {@code ServerStartedEvent} — so every world load
     * poisoned the corridor's entry before anything else ran, and the editor showed built-in
     * geometry however many times you saved.</p>
     *
     * <p>Callers should still ask at the right size — {@code CarriagePlacer.variantDims} is the
     * single answer to what that is — but getting it wrong is now a miss for that caller rather than
     * a session-long outage for everyone.</p>
     */
    public static Optional<StructureTemplate> get(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        boolean first = !STORE.isCached(variant.id());
        Optional<StructureTemplate> loaded = STORE.get(variant.id(), level);
        if (first && loaded.isEmpty()) {
            // First miss for this id this session: the store just cached "absent".
            LOGGER.warn("[DungeonTrain] No template found for variant={} in config or bundled — caller will fall back (legacy for built-ins, empty for customs).",
                variant.id());
        }
        return filterForDims(variant, loaded, dims);
    }

    /**
     * The size gate — <b>the only place</b> a template is judged against a caller's dims.
     *
     * <p>Deliberately not applied during the load: the cache is keyed by variant id and shared by
     * every caller, so a verdict that depends on the caller's dims must not be baked into it. See
     * {@link #get}.</p>
     *
     * <p>Returning empty falls the caller back to {@code legacyPlaceAt} for built-ins, or to a no-op
     * for customs.</p>
     */
    private static Optional<StructureTemplate> filterForDims(CarriageVariant variant, Optional<StructureTemplate> cached, CarriageDims dims) {
        if (cached.isEmpty()) return cached;
        if (CarriagePlacer.sizeMatches(cached.get().getSize(), dims)) return cached;
        Vec3i size = cached.get().getSize();
        LOGGER.warn(
            "[DungeonTrain] Template {} has bounds {}x{}x{}, caller expected {}x{}x{} — falling back.",
            variant.id(), size.getX(), size.getY(), size.getZ(),
            dims.length(), dims.height(), dims.width());
        return Optional.empty();
    }

    /** Write {@code template} to the per-install config-dir copy (repaired on the way out). */
    public static void save(CarriageVariant variant, StructureTemplate template) throws IOException {
        STORE.save(variant.id(), template);
    }

    /**
     * Write {@code template} to the source-tree copy that gets bundled into the
     * mod jar at build time. Only succeeds in a dev checkout where the source
     * tree is on disk and writable. Built-in entry point — see the
     * {@link #saveToSource(CarriageVariant, StructureTemplate) variant-keyed
     * overload} for the path that also handles custom variants.
     */
    public static void saveToSource(CarriageType type, StructureTemplate template) throws IOException {
        saveToSource(CarriageVariant.of(type), template);
    }

    /**
     * Variant-keyed source-tree write — works for both {@link CarriageVariant.Builtin}s
     * and {@link CarriageVariant.Custom}s. The dev-mode auto-write-through in
     * {@code CarriageEditor.save} uses this so a custom variant authored in the
     * editor (e.g. {@code cracked}, {@code fancywood}) lands in the source tree
     * alongside built-ins; once {@code <id>.nbt} is on disk, the
     * {@link SourcePolicy#SHIPS_WITH_GAME} gate in {@link CarriageVariantPartsStore} and
     * {@link CarriageVariantContentsAllowStore} starts returning true and the
     * sidecar promotes auto-cascade.
     */
    public static void saveToSource(CarriageVariant variant, StructureTemplate template) throws IOException {
        STORE.saveToSource(variant.id(), template);
    }

    /**
     * Copy the per-install config-dir copy of {@code type} into the source tree
     * so it ships with the next build. Built-in entry point — see the
     * {@link #promote(CarriageVariant) variant-keyed overload} for the path
     * that also handles custom variants.
     */
    public static void promote(CarriageType type) throws IOException {
        promote(CarriageVariant.of(type));
    }

    /**
     * Variant-keyed promote — works for both built-ins and customs. Copies
     * {@code config/dungeontrain/user/templates/<id>.nbt} into the source tree
     * so the next build ships it.
     */
    public static void promote(CarriageVariant variant) throws IOException {
        STORE.promote(variant.id());
    }

    /**
     * Source-tree path for a template id, Builtin or Custom. Public counterpart to
     * {@link #sourceFileFor(CarriageType)}, which only reaches the built-ins — mirrors
     * {@code CarriageContentsStore.sourceFileForId}.
     */
    public static Path sourceFileForId(String id) {
        return STORE.sourceFileFor(id);
    }

    public static boolean delete(CarriageVariant variant) throws IOException {
        return STORE.delete(variant.id());
    }

    public static boolean exists(CarriageVariant variant) {
        return STORE.existsInActive(variant.id());
    }

    /**
     * Load {@code variant}'s template from the bundled tier only — the
     * classpath {@code /data/dungeontrain/templates/<id>.nbt}. Skips the
     * config-dir override and the hardcoded fallback. Used by
     * {@code /dt reset default} to revert a plot to the shipped template
     * regardless of any user edits saved to the config dir.
     *
     * <p>Returns empty when the variant has no bundled copy — custom variants
     * always, and built-ins that have never been promoted.</p>
     */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        return filterForDims(variant, STORE.getBundled(variant.id(), level), dims);
    }

    /**
     * True iff the mod jar bundles a default template for {@code variant}.
     * Custom variants never have bundled copies.
     */
    public static boolean bundled(CarriageVariant variant) {
        if (!(variant instanceof CarriageVariant.Builtin)) return false;
        return STORE.bundled(variant.id());
    }

    /**
     * True iff the mod jar carries a carriage template named {@code id}, whichever kind of variant
     * it is registered as. Unlike {@link #bundled(CarriageVariant)}, a shipped custom variant counts:
     * this answers "is the name the mod's", not "does a built-in have a default to fall back to".
     */
    public static boolean shipsId(String id) {
        if (id == null || id.isEmpty()) return false;
        return STORE.bundled(id);
    }

    /**
     * Move a custom template file from {@code sourceId} to {@code targetId}.
     * Used by the editor's save-with-rename path for custom-to-custom
     * renames. Returns false if the source file does not exist.
     */
    public static boolean rename(String sourceId, String targetId) throws IOException {
        // A renamed template stays in its pool — set before the paths are resolved.
        ShellPool.set(targetId, ShellPool.poolOf(sourceId));
        return STORE.rename(sourceId, targetId);
    }

    /**
     * The checkout root a dev session runs from. Kept for the callers that resolve their own
     * source paths; new code should use {@link SourceTree} directly.
     */
    static Path projectRoot() {
        return SourceTree.projectRoot();
    }

    /**
     * Phase-2 adapter — exposes carriage save/promote through the unified
     * {@link TemplateStore} surface so {@code SaveCommand} can dispatch
     * without an {@code instanceof} chain. Custom variants ARE promotable in dev mode — see the
     * variant-keyed promote/saveToSource pair; outside dev mode there is no writable source tree,
     * so neither built-ins nor customs can promote.
     */
    private static final TemplateStore<Template.Carriage> ADAPTER = TemplateAdapters.of(
        TemplateKind.CARRIAGE,
        (player, t) -> CarriageEditor.save(player, t.variant()),
        TemplateAdapters.whenSourceWritable(t -> promote(t.variant())));

    public static TemplateStore<Template.Carriage> adapter() { return ADAPTER; }
}
