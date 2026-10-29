package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateAdapters;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.train.CarriageContents;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Three-tier contents template store, keyed on {@link CarriageContents}.
 *
 * <p>Parallel to {@link CarriageTemplateStore} but scoped to the carriage
 * <i>interior</i>. Files cover only the interior volume
 * {@code (length-2) × (height-2) × (width-2)} — the shell floor/walls/ceiling
 * are never captured here and come from the shell store.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/contents/<id>.nbt},
 *       per-install override. Custom contents only exist at this tier.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/contents/<id>.nbt}
 *       on the classpath. Ships inside the mod jar and represents the mod's
 *       defaults.</li>
 *   <li><b>Hardcoded fallback</b> — {@link games.brennan.dungeontrain.train.CarriageContentsPlacer#placeAt}
 *       drops to a legacy generator (single stone pressure plate at floor
 *       centre) when both above tiers miss. Only the {@code default} built-in
 *       falls back; custom contents with no config-dir file place nothing.</li>
 * </ol>
 *
 * Each read is filtered against the caller's interior dims; templates whose
 * recorded footprint doesn't match the world's current interior dims are
 * skipped so the next tier (or the hardcoded generator) gets a turn.
 *
 * <p>Policies: {@link SourcePolicy#NEVER} (write-through happens inside
 * {@link CarriageContentsEditor#save} when devmode is on), {@link DeleteCachePolicy#REMOVE}.
 * {@link #exists} asks the active package only.</p>
 */
public final class CarriageContentsStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SUBDIR = "contents";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    private static final ThreeTierStore<String, StructureTemplate> STORE =
        StoreDescriptor.<String, StructureTemplate>named("CarriageContentsStore")
            .id(id -> id)
            .subdir(SUBDIR)
            .fileName(id -> id + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .codec(StoreCodecs.structureTemplate(false))
            .logNoun("contents template")
            .sourcePolicy(SourcePolicy.NEVER)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .onSave(id -> ProvenanceCache.invalidateAll())
            .onDelete(id -> ProvenanceCache.invalidateAll())
            .onRename((a, b) -> ProvenanceCache.invalidateAll())
            .onClear(() -> {
                // Whether a template has a TV baked in is read from the template itself — re-read it too.
                games.brennan.dungeontrain.compat.vista.LiveTvWeighting.clearCache();
                // The vertical-flip copies are derived from the templates being dropped here — keeping
                // them would hand a stale flip back for a template that has just been re-authored.
                games.brennan.dungeontrain.train.ContentsFlip.clearCache();
            })
            .build();

    private CarriageContentsStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(CarriageContents contents) {
        return STORE.fileFor(contents.id());
    }

    public static Path fileForId(String id) {
        return STORE.fileFor(id);
    }

    /**
     * This contents template's raw NBT, with no size gate — for showing the template rather than
     * stamping it. See {@link TemplateNbt} for why the gate is skipped.
     */
    public static Optional<CompoundTag> rawTag(String id) {
        return TemplateNbt.read(SUBDIR, id + EXT, RESOURCE_PREFIX + id + EXT,
            "contents template " + id);
    }

    /**
     * On-disk path to the bundled-resource copy of {@code contents}'s template
     * inside the project source tree. Only meaningful in a dev checkout — see
     * {@link #sourceTreeAvailable()}.
     */
    public static Path sourceFileFor(CarriageContents contents) {
        return sourceFileForId(contents.id());
    }

    /**
     * Like {@link #sourceFileFor(CarriageContents)} but takes a raw id string
     * — used by saveAs / rename flows to delete the outgoing-name source file
     * without first having to materialise a {@link CarriageContents}.
     */
    public static Path sourceFileForId(String id) {
        return STORE.sourceFileFor(id);
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void reload() {
        STORE.clearCache();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    /**
     * Look up the template for {@code contents} constrained by the world's
     * interior dims. Returns empty if nothing on disk matches — caller falls
     * back to the hardcoded default-contents generator for the {@code default}
     * built-in, or places nothing for customs.
     *
     * <p>{@code interiorDims} is the carriage dims with the shell subtracted:
     * {@code length-2 × height-2 × width-2}. Wrapped in a {@link games.brennan.dungeontrain.train.CarriageDims}
     * only for reuse of the record; the shell minimums ({@code length>=4},
     * {@code width>=3}, {@code height>=3}) guarantee interior dims stay above
     * the {@code CarriageDims} floors of 4×3×3… except when width=3 (interior
     * width=1) — see the helper in {@link games.brennan.dungeontrain.train.CarriageContentsPlacer}
     * which uses a raw {@link Vec3i} instead of {@code CarriageDims} to avoid
     * the invariant.</p>
     *
     * <p><b>The cache holds what is on disk; the size question is answered per call.</b> The cache
     * is keyed by contents id alone and shared by every caller, so a verdict that depends on one
     * caller's interior size must not be baked into it. Not hypothetical: {@code portal} contents
     * are authored against the CORRIDOR's box ({@code CarriageContentsPlacer.contentsDims}), so any
     * sweep over all contents at ordinary carriage dims would otherwise take them out. There are
     * two gates — this one for a carriage, {@link #getFitting} for a portal room.</p>
     */
    public static Optional<StructureTemplate> get(
        ServerLevel level, CarriageContents contents, Vec3i interiorSize
    ) {
        return filterForSize(contents, STORE.get(contents.id(), level), interiorSize);
    }

    /**
     * {@link #get} with the size gate relaxed from "equals" to "fits inside" — the template is
     * returned whenever it is no bigger than {@code maxInterior} on any axis.
     *
     * <p>For the one caller whose box is not a carriage interior: a portal room furnished from the
     * contents pool ({@link games.brennan.dungeontrain.portal.PortalRoomContents}). A room interior
     * is larger than the carriage box these templates are authored against — 9×5×11 against 7×5×5 at
     * the default dims — so the exact gate would reject every one of them, and the room's setting is
     * precisely the author's instruction to place a smaller furnishing inside a larger space.</p>
     *
     * <p><b>Not a loosening of {@link #get}.</b> A carriage must still take only a template authored
     * at its own interior, or the stamp would leave a band of the shell unfurnished and the
     * variant sidecar's entries would land at the wrong cells. Where the template goes inside the
     * larger box is the caller's decision, not this store's — {@code PortalRoomContents.anchorsIn}
     * makes it.</p>
     *
     * <p>Quiet where {@link #filterForSize} warns: a template that does not fit is an ordinary
     * outcome here (a room may simply be smaller than a given furnishing), not the misconfiguration
     * a mismatched carriage template is.</p>
     */
    public static Optional<StructureTemplate> getFitting(
        ServerLevel level, CarriageContents contents, Vec3i maxInterior
    ) {
        Optional<StructureTemplate> cached = STORE.get(contents.id(), level);
        if (cached.isEmpty()) return cached;
        return fitsWithin(cached.get().getSize(), maxInterior) ? cached : Optional.empty();
    }

    private static boolean fitsWithin(Vec3i inner, Vec3i outer) {
        return inner.getX() <= outer.getX()
            && inner.getY() <= outer.getY()
            && inner.getZ() <= outer.getZ();
    }

    /** The size gate — the only place a contents template is judged against a caller's interior. */
    private static Optional<StructureTemplate> filterForSize(
        CarriageContents contents, Optional<StructureTemplate> cached, Vec3i interiorSize
    ) {
        if (cached.isEmpty()) return cached;
        if (sizeMatches(cached.get().getSize(), interiorSize)) return cached;
        Vec3i size = cached.get().getSize();
        LOGGER.warn(
            "[DungeonTrain] Contents template {} has bounds {}x{}x{}, caller expected interior {}x{}x{} — falling back.",
            contents.id(), size.getX(), size.getY(), size.getZ(),
            interiorSize.getX(), interiorSize.getY(), interiorSize.getZ());
        return Optional.empty();
    }

    /** Write {@code template} to the per-install config-dir copy. */
    public static void save(CarriageContents contents, StructureTemplate template) throws IOException {
        STORE.save(contents.id(), template);
    }

    /**
     * Write {@code template} to the source-tree copy that gets bundled into
     * the mod jar at build time. Only succeeds in a dev checkout where the
     * source tree is on disk and writable.
     */
    public static void saveToSource(CarriageContents contents, StructureTemplate template) throws IOException {
        STORE.saveToSource(contents.id(), template);
    }

    /**
     * Copy the per-install config-dir copy of {@code contents} into the source
     * tree so it ships with the next build. Works for both built-ins and
     * customs — unlike shell promotion which is built-in only — because
     * contents customs are first-class shippable assets.
     */
    public static void promote(CarriageContents contents) throws IOException {
        STORE.promote(contents.id());
        try {
            ContainerContentsStore.promoteFor("contents:" + contents.id());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Promoted contents template {} but failed to promote container sidecar: {}",
                contents.id(), e.toString());
        }
    }

    public static boolean delete(CarriageContents contents) throws IOException {
        return STORE.delete(contents.id());
    }

    public static boolean exists(CarriageContents contents) {
        return STORE.existsInActive(contents.id());
    }

    /** True iff the mod jar bundles a default template for {@code contents}. */
    public static boolean bundled(CarriageContents contents) {
        return STORE.bundled(contents.id());
    }

    public static boolean rename(String sourceId, String targetId) throws IOException {
        return STORE.rename(sourceId, targetId);
    }

    private static boolean sizeMatches(Vec3i a, Vec3i b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    /**
     * Phase-2 adapter — exposes contents save/promote through the unified
     * {@link TemplateStore} surface. Contents have no separate bundled tier
     * for {@code /dt save default} (write-through happens inside {@link CarriageContentsEditor#save}
     * when devmode is on), so {@code canPromote} is false and {@code promote} throws — mirrors the
     * existing {@code SaveCommand} arm for {@code Template.Contents}.
     */
    private static final TemplateStore<Template.Contents> ADAPTER = TemplateAdapters.noBundledTier(
        TemplateKind.CONTENTS,
        (player, t) -> CarriageContentsEditor.save(player, t.contents()),
        "Contents have no bundled tier — '/dt save default' does not apply.");

    public static TemplateStore<Template.Contents> adapter() { return ADAPTER; }
}
