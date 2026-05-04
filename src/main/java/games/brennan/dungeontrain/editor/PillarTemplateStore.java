package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Editor + generator-facing facade over {@link TrackVariantStore} for pillar
 * sections and the stairs adjunct. Mirrors {@link TrackTemplateStore}: the
 * single-arg APIs ({@code get(level, section, dims)} etc.) keep authoring
 * the synthetic "default" name so {@link PillarEditor} doesn't need to
 * know about variants, while the per-name overloads
 * ({@code getColumnFor(level, section, dims, name)}) feed
 * {@link games.brennan.dungeontrain.track.TrackGenerator} when it picks a
 * registry-weighted variant per pillar.
 *
 * <p>Per-section/-adjunct unpacked-cell cache keyed by
 * {@code <kind>/<name>}; cleared on {@code ServerStoppedEvent} via
 * {@link games.brennan.dungeontrain.event.WorldLifecycleEvents}.
 * Reflection on {@link StructureTemplate#palettes} replicates the pattern
 * from {@link TrackTemplateStore} since vanilla 1.20.1 doesn't expose the
 * field publicly and we don't ship an AT.</p>
 *
 * <p>Legacy file migration ({@code pillars/pillar_<section>.nbt} →
 * {@code pillars/<section>/default.nbt} and
 * {@code pillars/adjunct_stairs.nbt} →
 * {@code pillars/adjunct_stairs/default.nbt}) lives in
 * {@link TrackVariantStore#migrateLegacyPaths()}, called from the variant
 * registry's server-start hook before this store ever sees a request.</p>
 */
public final class PillarTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-section unpacked column cache, keyed by {@code <section>/<name>}. */
    private static final Map<String, Optional<BlockState[][]>> COLUMN_CACHE = new HashMap<>();

    /**
     * Reflected accessor for {@link StructureTemplate#palettes}. Private in
     * vanilla 1.20.1; we don't ship an AT.
     */
    private static Field palettesField;

    private PillarTemplateStore() {}

    public static Path directory() {
        return TrackVariantStore.directory(TrackKind.PILLAR_TOP).getParent();
    }

    /** Path to the synthetic-default NBT for {@code section}. */
    public static Path fileFor(PillarSection section) {
        return TrackVariantStore.fileFor(pillarKind(section), TrackKind.DEFAULT_NAME);
    }

    public static Path sourceFileFor(PillarSection section) {
        return TrackVariantStore.sourceFileFor(pillarKind(section), TrackKind.DEFAULT_NAME);
    }

    public static boolean sourceTreeAvailable() {
        return TrackVariantStore.sourceTreeAvailable();
    }

    public static synchronized void clearCache() {
        COLUMN_CACHE.clear();
    }

    /**
     * Pre-0.30 → 0.30 migration: move pillar NBTs that were stored in the
     * carriage-templates dir ({@code config/dungeontrain/templates/pillar_*.nbt})
     * into the pillars dir so {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}
     * doesn't accidentally register them as carriages and fail placement.
     *
     * <p>Only handles the legacy {@code templates/} → {@code pillars/} step.
     * The newer {@code pillars/pillar_<section>.nbt} → {@code pillars/<section>/default.nbt}
     * migration runs in {@link TrackVariantStore#migrateLegacyPaths()},
     * which is called immediately after this from
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantRegistry#reload()}.</p>
     */
    public static synchronized void migrateFromLegacyDirectory() {
        Path legacyDir = FMLPaths.CONFIGDIR.get().resolve("dungeontrain/templates");
        if (!Files.isDirectory(legacyDir)) return;
        Path newDir = FMLPaths.CONFIGDIR.get().resolve("dungeontrain/pillars");
        for (PillarSection section : PillarSection.values()) {
            Path legacy = legacyDir.resolve("pillar_" + section.id() + ".nbt");
            if (!Files.isRegularFile(legacy)) continue;
            Path target = newDir.resolve("pillar_" + section.id() + ".nbt");
            try {
                Files.createDirectories(newDir);
                if (Files.isRegularFile(target)) {
                    Files.delete(legacy);
                    LOGGER.info("[DungeonTrain] Pillar template {} already present at {} — removed stale legacy copy at {}",
                        section.id(), target, legacy);
                } else {
                    Files.move(legacy, target);
                    LOGGER.info("[DungeonTrain] Migrated pillar template {} from {} to {}",
                        section.id(), legacy, target);
                }
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to migrate pillar template {} from {} to {}: {}",
                    section.id(), legacy, target, e.toString());
            }
        }
    }

    /** Path to the synthetic-default NBT for {@code adjunct}. */
    public static Path fileFor(PillarAdjunct adjunct) {
        return TrackVariantStore.fileFor(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    public static Path sourceFileFor(PillarAdjunct adjunct) {
        return TrackVariantStore.sourceFileFor(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    /**
     * Editor-facing — return the unpacked column for the synthetic "default"
     * variant. Equivalent to
     * {@link #getColumnFor(ServerLevel, PillarSection, CarriageDims, String)
     * getColumnFor(level, section, dims, DEFAULT_NAME)}.
     */
    public static synchronized Optional<BlockState[][]> getColumn(
        ServerLevel level, PillarSection section, CarriageDims dims
    ) {
        return getColumnFor(level, section, dims, TrackKind.DEFAULT_NAME);
    }

    /**
     * Generator-facing — unpack the named section's NBT into a
     * {@code BlockState[height][width]} array. {@code column[0]} is the bottom
     * row (template-local Y=0); {@code column[y][0]} is the Z=0 position
     * within the track span. Positions captured as air at save time stay
     * {@code null}.
     */
    public static synchronized Optional<BlockState[][]> getColumnFor(
        ServerLevel level, PillarSection section, CarriageDims dims, String name
    ) {
        String key = cacheKey(section, name);
        Optional<BlockState[][]> cached = COLUMN_CACHE.get(key);
        if (cached != null) {
            if (cached.isEmpty()) return cached;
            BlockState[][] c = cached.get();
            if (c.length == section.height() && c[0].length == dims.width()) {
                return cached;
            }
            // Dim mismatch — fall through and rebuild below.
        }
        Optional<StructureTemplate> tmpl =
            TrackVariantStore.get(level, pillarKind(section), name, dims);
        Optional<BlockState[][]> column =
            tmpl.map(t -> extractColumn(t, section.height(), dims.width()));
        COLUMN_CACHE.put(key, column);
        return column;
    }

    /** Editor-facing — load the StructureTemplate for "default". */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, PillarSection section, CarriageDims dims
    ) {
        return TrackVariantStore.get(level, pillarKind(section), TrackKind.DEFAULT_NAME, dims);
    }

    public static synchronized void save(PillarSection section, StructureTemplate template) throws IOException {
        TrackVariantStore.save(pillarKind(section), TrackKind.DEFAULT_NAME, template);
        COLUMN_CACHE.remove(cacheKey(section, TrackKind.DEFAULT_NAME));
    }

    public static synchronized void saveToSource(PillarSection section, StructureTemplate template) throws IOException {
        TrackVariantStore.saveToSource(pillarKind(section), TrackKind.DEFAULT_NAME, template);
    }

    public static synchronized void promote(PillarSection section) throws IOException {
        TrackVariantStore.promote(pillarKind(section), TrackKind.DEFAULT_NAME);
    }

    public static synchronized boolean delete(PillarSection section) throws IOException {
        boolean existed = TrackVariantStore.delete(pillarKind(section), TrackKind.DEFAULT_NAME);
        COLUMN_CACHE.put(cacheKey(section, TrackKind.DEFAULT_NAME), Optional.empty());
        return existed;
    }

    public static boolean exists(PillarSection section) {
        return TrackVariantStore.exists(pillarKind(section), TrackKind.DEFAULT_NAME);
    }

    public static Optional<StructureTemplate> getBundled(
        ServerLevel level, PillarSection section, CarriageDims dims
    ) {
        return TrackVariantStore.getBundled(level, pillarKind(section), TrackKind.DEFAULT_NAME, dims);
    }

    public static boolean bundled(PillarSection section) {
        return TrackVariantStore.bundled(pillarKind(section), TrackKind.DEFAULT_NAME);
    }

    /** Editor-facing — load the adjunct's "default" StructureTemplate. */
    public static synchronized Optional<StructureTemplate> getAdjunct(ServerLevel level, PillarAdjunct adjunct) {
        return getAdjunctFor(level, adjunct, TrackKind.DEFAULT_NAME);
    }

    /** Generator-facing — load the named adjunct StructureTemplate. */
    public static synchronized Optional<StructureTemplate> getAdjunctFor(
        ServerLevel level, PillarAdjunct adjunct, String name
    ) {
        // Adjunct dims are fixed (don't depend on world dims) — pass a dummy
        // CarriageDims at min legal values since the kind ignores it for
        // ADJUNCT_STAIRS. clamp() avoids the canonical-constructor throw on
        // out-of-range components.
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackVariantStore.get(level, adjunctKind(adjunct), name, dims);
    }

    public static synchronized void saveAdjunct(PillarAdjunct adjunct, StructureTemplate template) throws IOException {
        TrackVariantStore.save(adjunctKind(adjunct), TrackKind.DEFAULT_NAME, template);
    }

    public static synchronized void saveAdjunctToSource(PillarAdjunct adjunct, StructureTemplate template) throws IOException {
        TrackVariantStore.saveToSource(adjunctKind(adjunct), TrackKind.DEFAULT_NAME, template);
    }

    public static synchronized void promoteAdjunct(PillarAdjunct adjunct) throws IOException {
        TrackVariantStore.promote(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    public static synchronized boolean deleteAdjunct(PillarAdjunct adjunct) throws IOException {
        return TrackVariantStore.delete(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    public static boolean existsAdjunct(PillarAdjunct adjunct) {
        return TrackVariantStore.exists(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    public static boolean bundledAdjunct(PillarAdjunct adjunct) {
        return TrackVariantStore.bundled(adjunctKind(adjunct), TrackKind.DEFAULT_NAME);
    }

    /**
     * Bundled-tier load for {@code adjunct}'s synthetic-default template.
     * Mirrors {@link #getBundled(ServerLevel, PillarSection, CarriageDims)}
     * but for adjuncts; pass through to {@link TrackVariantStore#getBundled}
     * with a sentinel {@link CarriageDims} since adjunct kinds have fixed
     * dimensions and ignore world dims.
     */
    public static Optional<StructureTemplate> getBundledAdjunct(
        ServerLevel level, PillarAdjunct adjunct
    ) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackVariantStore.getBundled(level, adjunctKind(adjunct), TrackKind.DEFAULT_NAME, dims);
    }

    /** Map a {@link PillarSection} to its {@link TrackKind}. */
    public static TrackKind pillarKind(PillarSection section) {
        return switch (section) {
            case TOP -> TrackKind.PILLAR_TOP;
            case MIDDLE -> TrackKind.PILLAR_MIDDLE;
            case BOTTOM -> TrackKind.PILLAR_BOTTOM;
        };
    }

    /** Map a {@link PillarAdjunct} to its {@link TrackKind}. */
    public static TrackKind adjunctKind(PillarAdjunct adjunct) {
        return switch (adjunct) {
            case STAIRS -> TrackKind.ADJUNCT_STAIRS;
        };
    }

    private static String cacheKey(PillarSection section, String name) {
        return section.id() + "/" + name;
    }

    /**
     * Unpack the first palette of {@code template} into a
     * {@code BlockState[height][width]} array. {@code column[0]} is the
     * bottom row (template-local Y=0); {@code column[y][0]} is Z=0. Positions
     * not present in the template stay {@code null}.
     */
    /**
     * Phase-2 adapter — exposes pillar-section save/promote through the
     * unified {@link TemplateStore} surface. Cached per
     * {@link PillarSection} (TOP/MIDDLE/BOTTOM); each instance stamps the
     * correct section into {@code TrackVariantStore} via the existing
     * {@code save(section, template)} static.
     */
    private static final EnumMap<PillarSection, TemplateStore<Template.Pillar>> SECTION_ADAPTERS
        = new EnumMap<>(PillarSection.class);
    static {
        for (PillarSection s : PillarSection.values()) SECTION_ADAPTERS.put(s, makeSectionAdapter(s));
    }

    private static TemplateStore<Template.Pillar> makeSectionAdapter(PillarSection section) {
        return new TemplateStore<>() {
            @Override public TemplateKind kind() { return TemplateKind.PILLAR; }

            @Override
            public SaveResult save(ServerPlayer player, Template.Pillar template) throws Exception {
                // Phase-4 Bug fix: use the explicit-name save so Stores.save
                // works for non-default-named pillar variants (template label
                // menu, save-all iteration, save-model command).
                PillarEditor.SaveResult r = PillarEditor.save(player,
                    new games.brennan.dungeontrain.template.PillarTemplateId(section, template.name()));
                return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
            }

            @Override
            public boolean canPromote(Template.Pillar template) { return sourceTreeAvailable(); }

            @Override
            public void promote(Template.Pillar template) throws Exception {
                PillarTemplateStore.promote(section);
            }
        };
    }

    public static TemplateStore<Template.Pillar> adapter(PillarSection section) {
        return SECTION_ADAPTERS.get(section);
    }

    /**
     * Phase-3 record-shaped overload: {@link #adapter(PillarSection)} keyed
     * via the {@link games.brennan.dungeontrain.template.PillarTemplateId}
     * record. Underlying EnumMap cache key stays the bare
     * {@link PillarSection}.
     */
    public static TemplateStore<Template.Pillar> adapter(games.brennan.dungeontrain.template.PillarTemplateId id) {
        return SECTION_ADAPTERS.get(id.section());
    }

    /**
     * Phase-2 adapter — exposes adjunct (stairs) save/promote through the
     * unified {@link TemplateStore} surface. Cached per
     * {@link PillarAdjunct}; delegates to the existing
     * {@code saveAdjunct} / {@code promoteAdjunct} statics so the storage
     * path stays {@code config/dungeontrain/pillars/adjunct_stairs/}.
     */
    private static final EnumMap<PillarAdjunct, TemplateStore<Template.Adjunct>> ADJUNCT_ADAPTERS
        = new EnumMap<>(PillarAdjunct.class);
    static {
        for (PillarAdjunct a : PillarAdjunct.values()) ADJUNCT_ADAPTERS.put(a, makeAdjunctAdapter(a));
    }

    private static TemplateStore<Template.Adjunct> makeAdjunctAdapter(PillarAdjunct adjunct) {
        return new TemplateStore<>() {
            @Override public TemplateKind kind() { return TemplateKind.STAIRS; }

            @Override
            public SaveResult save(ServerPlayer player, Template.Adjunct template) throws Exception {
                // Phase-4 Bug fix: pass template.name() through so non-default
                // adjunct variants save correctly via Stores.save.
                PillarEditor.SaveResult r = PillarEditor.save(player,
                    new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(adjunct, template.name()));
                return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
            }

            @Override
            public boolean canPromote(Template.Adjunct template) { return sourceTreeAvailable(); }

            @Override
            public void promote(Template.Adjunct template) throws Exception {
                PillarTemplateStore.promoteAdjunct(adjunct);
            }
        };
    }

    public static TemplateStore<Template.Adjunct> adapterForAdjunct(PillarAdjunct adjunct) {
        return ADJUNCT_ADAPTERS.get(adjunct);
    }

    /**
     * Record-shaped overload for adjunct adapters. The
     * {@link games.brennan.dungeontrain.template.PillarAdjunctTemplateId}
     * record carries the {@link PillarAdjunct} discriminator, so this
     * resolves to the matching per-adjunct adapter.
     */
    public static TemplateStore<Template.Adjunct> adapterForAdjunct(games.brennan.dungeontrain.template.PillarAdjunctTemplateId id) {
        return ADJUNCT_ADAPTERS.get(id.adjunct());
    }

    private static BlockState[][] extractColumn(StructureTemplate template, int height, int width) {
        BlockState[][] column = new BlockState[height][width];
        try {
            Field field = palettesField;
            if (field == null) {
                field = StructureTemplate.class.getDeclaredField("palettes");
                field.setAccessible(true);
                palettesField = field;
            }
            @SuppressWarnings("unchecked")
            List<StructureTemplate.Palette> palettes =
                (List<StructureTemplate.Palette>) field.get(template);
            if (palettes.isEmpty()) return column;
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                int y = info.pos().getY();
                int z = info.pos().getZ();
                if (y >= 0 && y < height && z >= 0 && z < width) {
                    column[y][z] = info.state();
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error(
                "[DungeonTrain] Unable to extract pillar template column (palettes field unreachable): {}",
                e.toString()
            );
        }
        return column;
    }
}
