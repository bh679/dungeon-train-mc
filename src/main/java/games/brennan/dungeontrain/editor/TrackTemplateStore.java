package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Editor-facing facade for the track tile template. Delegates underlying NBT
 * I/O to {@link TrackVariantStore} for {@link TrackKind#TILE} so the editor's
 * single-plot UI keeps authoring the synthetic "default" name while
 * {@link games.brennan.dungeontrain.track.TrackGenerator} can pick any
 * registered name per tile.
 *
 * <p>Cells are unpacked into a {@code BlockState[TILE_LENGTH][HEIGHT][width]}
 * array using reflection on {@link StructureTemplate#palettes} (private in
 * vanilla 1.20.1; we don't ship an AT). Per-name unpacked-cell cache lives
 * here so repeated stamps inside one chunk don't repeat the unpack work.</p>
 *
 * <p>Migration of the legacy {@code config/dungeontrain/tracks/track.nbt}
 * over to {@code tracks/default.nbt} is handled by
 * {@link TrackVariantStore#migrateLegacyPaths()}, called from
 * {@link games.brennan.dungeontrain.track.variant.TrackVariantRegistry#reload()}
 * at server start.</p>
 */
public final class TrackTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-name cache of unpacked cells, keyed by lowercase variant name. */
    private static final Map<String, Optional<BlockState[][][]>> CELLS_CACHE = new HashMap<>();

    /**
     * Reflected accessor for {@link StructureTemplate#palettes}. Private in
     * vanilla 1.20.1; we don't ship an AT.
     */
    private static Field palettesField;

    private TrackTemplateStore() {}

    /** Config dir for track tile NBTs ({@code config/dungeontrain/tracks/}). */
    public static Path directory() {
        return TrackVariantStore.directory(TrackKind.TILE);
    }

    /** Path to the synthetic-default track NBT under the config dir. */
    public static Path file() {
        return TrackVariantStore.fileFor(TrackKind.TILE, TrackKind.DEFAULT_NAME);
    }

    /** Path to the synthetic-default track NBT under the source tree (dev mode). */
    public static Path sourceFile() {
        return TrackVariantStore.sourceFileFor(TrackKind.TILE, TrackKind.DEFAULT_NAME);
    }

    public static boolean sourceTreeAvailable() {
        return TrackVariantStore.sourceTreeAvailable();
    }

    /** Release every cached unpacked-cells entry. Wired to {@code ServerStoppedEvent}. */
    public static synchronized void clearCache() {
        CELLS_CACHE.clear();
    }

    /**
     * Editor-facing — return the unpacked cells for the synthetic "default"
     * variant. Equivalent to {@link #getCellsFor(ServerLevel, CarriageDims, String)
     * getCellsFor(level, dims, TrackKind.DEFAULT_NAME)}; preserved as the
     * one-arg call so {@link TrackEditor} doesn't need to know about variant
     * names.
     */
    public static synchronized Optional<BlockState[][][]> getCells(ServerLevel level, CarriageDims dims) {
        return getCellsFor(level, dims, TrackKind.DEFAULT_NAME);
    }

    /**
     * Generator-facing — unpack the named tile's NBT into a
     * {@code BlockState[TILE_LENGTH][HEIGHT][width]} array. {@code cells[0][0][0]}
     * is the tile origin (bed row, Z=0). Positions captured as air at save
     * time stay {@code null} so callers can decide whether that means
     * "skip" or "fall back".
     *
     * <p>Empty when no config-dir or bundled template exists at the current
     * world's {@code dims.width()}; caller falls back to the hardcoded
     * stone-brick bed + vanilla rail behavior.</p>
     */
    public static synchronized Optional<BlockState[][][]> getCellsFor(
        ServerLevel level, CarriageDims dims, String name
    ) {
        String key = name == null ? TrackKind.DEFAULT_NAME : name;
        Optional<BlockState[][][]> cached = CELLS_CACHE.get(key);
        if (cached != null) {
            if (cached.isEmpty()) return cached;
            BlockState[][][] c = cached.get();
            if (c.length == TrackPlacer.TILE_LENGTH
                && c[0].length == TrackPlacer.HEIGHT
                && c[0][0].length == dims.width()) {
                return cached;
            }
            // Dim mismatch — fall through and rebuild below.
        }
        Optional<StructureTemplate> tmpl = TrackVariantStore.get(level, TrackKind.TILE, key, dims);
        Optional<BlockState[][][]> cells = tmpl.map(t -> extractCells(t, dims.width()));
        CELLS_CACHE.put(key, cells);
        return cells;
    }

    /** Editor-facing — load the StructureTemplate for "default". */
    public static synchronized Optional<StructureTemplate> get(ServerLevel level, CarriageDims dims) {
        return TrackVariantStore.get(level, TrackKind.TILE, TrackKind.DEFAULT_NAME, dims);
    }

    /** Save the editor's captured template back as the "default" variant. */
    public static synchronized void save(StructureTemplate template) throws IOException {
        TrackVariantStore.save(TrackKind.TILE, TrackKind.DEFAULT_NAME, template);
        CELLS_CACHE.remove(TrackKind.DEFAULT_NAME);
        LOGGER.info("[DungeonTrain] Saved track template (default) to {}", file());
    }

    /** Save the editor's captured template into the source tree (dev mode). */
    public static synchronized void saveToSource(StructureTemplate template) throws IOException {
        TrackVariantStore.saveToSource(TrackKind.TILE, TrackKind.DEFAULT_NAME, template);
    }

    /** Promote the per-install override to the source tree (dev mode). */
    public static synchronized void promote() throws IOException {
        TrackVariantStore.promote(TrackKind.TILE, TrackKind.DEFAULT_NAME);
    }

    /** Delete the per-install "default" track NBT. Returns true if it existed. */
    public static synchronized boolean delete() throws IOException {
        boolean existed = TrackVariantStore.delete(TrackKind.TILE, TrackKind.DEFAULT_NAME);
        CELLS_CACHE.put(TrackKind.DEFAULT_NAME, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted track template (default) at {}", file());
        return existed;
    }

    public static boolean exists() {
        return TrackVariantStore.exists(TrackKind.TILE, TrackKind.DEFAULT_NAME);
    }

    /** Bundled-only "default" lookup — used by {@code /dt reset}. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, CarriageDims dims) {
        return TrackVariantStore.getBundled(level, TrackKind.TILE, TrackKind.DEFAULT_NAME, dims);
    }

    public static boolean bundled() {
        return TrackVariantStore.bundled(TrackKind.TILE, TrackKind.DEFAULT_NAME);
    }

    /**
     * Unpack the first palette of {@code template} into a
     * {@code BlockState[TILE_LENGTH][HEIGHT][width]} array. {@code cells[0][0][0]}
     * is the tile origin (bed row, Z=0). Positions not present in the template
     * (recorded as air at save time) stay {@code null}.
     *
     * <p>Uses reflection on {@link StructureTemplate#palettes} because the
     * field is private and we don't ship an access transformer.</p>
     */
    private static BlockState[][][] extractCells(StructureTemplate template, int width) {
        BlockState[][][] cells = new BlockState[TrackPlacer.TILE_LENGTH][TrackPlacer.HEIGHT][width];
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
            if (palettes.isEmpty()) return cells;
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                int x = info.pos().getX();
                int y = info.pos().getY();
                int z = info.pos().getZ();
                if (x >= 0 && x < TrackPlacer.TILE_LENGTH
                    && y >= 0 && y < TrackPlacer.HEIGHT
                    && z >= 0 && z < width) {
                    cells[x][y][z] = info.state();
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error(
                "[DungeonTrain] Unable to extract track template cells (palettes field unreachable): {}",
                e.toString()
            );
        }
        return cells;
    }

    /**
     * Phase-2 adapter — exposes the track-tile store through the unified
     * {@link TemplateStore} surface. Track has only the synthetic
     * "default" name (per {@link TrackKind#DEFAULT_NAME}), so the
     * {@link Template.TrackModel#name()} field isn't dispatched on yet —
     * the editor save still hits the single-tile flow.
     */
    private static final TemplateStore<Template.TrackModel> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.TRACK; }

        @Override
        public SaveResult save(ServerPlayer player, Template.TrackModel template) throws Exception {
            TrackEditor.SaveResult r = TrackEditor.save(player);
            return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
        }

        @Override
        public boolean canPromote(Template.TrackModel template) { return sourceTreeAvailable(); }

        @Override
        public void promote(Template.TrackModel template) throws Exception {
            TrackTemplateStore.promote();
        }
    };

    public static TemplateStore<Template.TrackModel> adapter() { return ADAPTER; }
}
