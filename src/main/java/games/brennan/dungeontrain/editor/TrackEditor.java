package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Editor plots for the open-air track tile. Mirrors
 * {@link CarriagePartEditor}: one plot per registered variant name in
 * {@link TrackVariantRegistry}, laid out along {@code +Z} with
 * {@link EditorLayout#GAP}-block spacing between footprints, sharing the
 * column at {@link TrackSidePlots#X_TRACK}. Plot positioning lives in
 * {@link TrackSidePlots} so every track-side editor uses the same grid.
 *
 * <p>The return point is recorded in {@link EditorSessions}, so a single
 * {@code /dungeontrain editor exit} restores the player no matter which
 * sub-editor they entered last.</p>
 */
public final class TrackEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrackEditor() {}

    /** Plot origin for the synthetic-default variant. */
    public static BlockPos plotOrigin(CarriageDims dims) {
        return TrackSidePlots.plotOriginDefault(TrackKind.TILE, dims);
    }

    /** Plot origin for {@code (TILE, name)}. Used by the new/remove command. */
    public static BlockPos plotOrigin(String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
    }

    /**
     * True if {@code pos} sits inside any registered track-tile variant's
     * plot. Used as the legacy entry point for back-compat callers; new
     * code should prefer {@link TrackSidePlots#locate} which returns the
     * matched name.
     */
    public static boolean plotContaining(BlockPos pos, CarriageDims dims) {
        return resolveName(pos, dims) != null;
    }

    /**
     * Resolved variant name for the plot {@code pos} sits inside, or null
     * if outside every track-tile plot. Includes the 1-block outline-cage
     * margin used uniformly across the track-side grid.
     */
    public static String resolveName(BlockPos pos, CarriageDims dims) {
        // Answers only while TRACKS is the resident category — every category shares the origin.
        if (!EditorStampedCategoryState.isActive(EditorCategory.TRACKS)) return null;
        Vec3i footprint = TrackKind.TILE.dims(dims);
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            BlockPos o = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            if (EditorPlotBox.contains(pos, o, footprint)) return name;
        }
        return null;
    }

    /**
     * Teleport {@code player} to the default-variant plot. Stamps every
     * registered tile variant's plot first so the row is fully visible.
     */
    public static void enter(ServerPlayer player) {
        enter(player, true);
    }

    /**
     * Always restamps: this is the reload every command and post-download jump means, whether or
     * not the player is already standing in the default plot — a relay Load that installed a new
     * variant arrives here and its plot must be stamped. The walk that keeps unsaved edits is
     * {@link #walkTo}.
     */
    public static void enter(ServerPlayer player, boolean onTop) {
        enter(player, onTop, true);
    }

    /**
     * Go here / the panel's Enter: a walk to the default tile plot, not a reload — restamps only
     * when the player is not already standing in it.
     */
    public static void walkTo(ServerPlayer player, boolean onTop) {
        enter(player, onTop, !EditorPlotScope.standingIn(player, new Template.Track()));
    }

    /**
     * @param stamp whether to erase + restamp the plots before teleporting. The category entry
     *              passes {@code false}: it has stamped, or queued, every plot itself.
     */
    public static void enter(ServerPlayer player, boolean onTop, boolean stamp) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        EditorSessions.rememberReturn(player);
        if (stamp) stampAllPlots(overworld, dims);

        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, TrackKind.DEFAULT_NAME, dims);
        // The label's footprint, so the roof landing sits in front of the panel it draws.
        Vec3i footprint = TrackSidePlots.footprint(TrackKind.TILE, TrackKind.DEFAULT_NAME, dims);
        EditorPlotArrival.land(player, overworld, origin, footprint, onTop, EditorPlotArrival.Inside.CENTRE, null);

        LOGGER.info("[DungeonTrain] Track editor enter: {} -> default plot at {} ({} variants registered, {})",
            player.getName().getString(), origin,
            TrackVariantRegistry.namesFor(TrackKind.TILE).size(),
            onTop ? "top" : "inside");
    }

    /**
     * Erase + restamp every registered track-tile variant plot. Idempotent.
     * Called from {@link #enter} and from category-wide stamps
     * ({@code /dt editor tracks}).
     */
    public static void stampPlot(ServerLevel overworld, CarriageDims dims) {
        stampAllPlots(overworld, dims);
    }

    /** Erase + restamp the single plot for {@code name}. */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
        // Drop in-session contents-store changes (loot-prefab links) so the
        // re-stamp reads the last-saved disk state; placement writes only
        // touch the in-memory cache until /save.
        ContainerContentsStore.invalidate(BlockVariantPlot.trackKey(TrackKind.TILE, name));
        eraseAt(overworld, origin, dims);
        EditorPlotEntityClearer.discardNonPlayersIn(
            overworld, origin.offset(-1, -1, -1),
            new Vec3i(TrackPlacer.TILE_LENGTH + 2, TrackPlacer.HEIGHT + 2, dims.width() + 2));
        stampNameAt(overworld, origin, name, dims);
        setOutline(overworld, origin, dims);
        captureSnapshot(overworld, origin, name, dims);
    }

    /** Erase every track-tile plot — footprint + outline cage cleared to air. */
    public static void clearPlot(ServerLevel overworld, CarriageDims dims) {
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            eraseAt(overworld, origin, dims);
            EditorPlotSnapshots.clear(snapshotKey(name));
        }
    }

    /** Erase a single named track-tile plot — footprint + outline cleared to air. */
    public static void clearPlot(ServerLevel overworld, String name, CarriageDims dims) {
        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
        eraseAt(overworld, origin, dims);
        EditorPlotSnapshots.clear(snapshotKey(name));
    }

    /** Snapshot the freshly-stamped {@code (length × height × width)} region for {@link EditorDirtyCheck}'s baseline. */
    private static void captureSnapshot(ServerLevel overworld, BlockPos origin, String name, CarriageDims dims) {
        EditorPlotSnapshots.capture(snapshotKey(name), overworld, origin,
            TrackPlacer.TILE_LENGTH, TrackPlacer.HEIGHT, dims.width());
    }

    /** Snapshot key shared by {@link EditorDirtyCheck} for track-tile rows. */
    public static String snapshotKey(String name) {
        return new Template.Track(name).snapshotKey().orElseThrow();
    }

    /**
     * Capture the {@code 4 × 2 × width} region at the plot the player is
     * standing in into a fresh {@link StructureTemplate} and persist it
     * via {@link TrackVariantStore} for the resolved {@code name}.
     */
    public static SaveResult save(ServerPlayer player) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = resolveName(player.blockPosition(), dims);
        if (name == null) throw new IOException("Player is not inside any track-tile plot.");

        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
        Vec3i footprint = TrackKind.TILE.dims(dims);
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.TILE, name, footprint);
        StructureTemplate template = captureTemplate(overworld, origin, dims);
        TrackVariantStore.save(TrackKind.TILE, name, template);

        // Contents store: persist any in-session loot-prefab link changes
        // accumulated since enter (PrefabUseHandler defers its writes until
        // /save). Failure is logged but doesn't fail the whole save.
        try {
            ContainerContentsStore.loadFor(BlockVariantPlot.trackKey(TrackKind.TILE, name)).save();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Track editor save: contents-store save failed for {}: {}",
                name, e.toString());
        }

        // Refresh the dirty-check baseline so the just-saved state reads as
        // clean on the next /dt editor unsaved-list query.
        captureSnapshot(overworld, origin, name, dims);

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_track");
        // …and, when they have opted in, to the player's relay profile. Hooked here rather than at
        // the callers because both ways in land on this method — see EditorRelaySave.
        EditorRelaySave.afterSave(player, new Template.Track(name));
        LOGGER.info("[DungeonTrain] Track editor save: {} -> tile/{} ({}x{}x{})",
            player.getName().getString(), name,
            TrackPlacer.TILE_LENGTH, TrackPlacer.HEIGHT, dims.width());

        return EditorSave.toSource("Track editor save: tile/" + name,
            () -> TrackVariantStore.saveToSource(TrackKind.TILE, name, template),
            () -> sidecar.saveToSource(TrackKind.TILE, name));
    }

    private static void stampAllPlots(ServerLevel overworld, CarriageDims dims) {
        // A category fill still in flight must land before a whole-kind restamp walks the same plots.
        EditorStampQueue.flush();
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.TILE);
        for (String name : names) {
            BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            eraseAt(overworld, origin, dims);
            stampNameAt(overworld, origin, name, dims);
            setOutline(overworld, origin, dims);
            captureSnapshot(overworld, origin, name, dims);
        }
    }

    private static void eraseAt(ServerLevel level, BlockPos origin, CarriageDims dims) {
        EditorPlotBox.erase(level, origin, TrackKind.TILE.dims(dims), EditorPlotCage.NOTIFY);
    }

    private static void stampNameAt(ServerLevel level, BlockPos origin, String name, CarriageDims dims) {
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, TrackKind.TILE, name, dims);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            TemplateStamp.placeWithDecor(level, origin, stored.get(), settings);
            return;
        }
        // Fallback for unauthored "default" — hardcoded bed + 2-rail stamp.
        int w = dims.width();
        for (int dx = 0; dx < TrackPlacer.TILE_LENGTH; dx++) {
            for (int dz = 0; dz < w; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), TrackPalette.BED, 3);
                if (dz == 1 || dz == w - 2) {
                    level.setBlock(origin.offset(dx, 1, dz), TrackPalette.RAIL, 3);
                }
            }
        }
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriageDims dims) {
        Vec3i size = new Vec3i(TrackPlacer.TILE_LENGTH, TrackPlacer.HEIGHT, dims.width());
        return TemplateDecor.capture(level, origin, size, Blocks.AIR);
    }

    private static void setOutline(ServerLevel level, BlockPos origin, CarriageDims dims) {
        EditorPlotCage.setOutline(level, origin, TrackKind.TILE.dims(dims), EditorPlotCage.OUTLINE_BLOCK);
    }
}
