package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.template.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Editor plots for {@link CarriageVariant}s — fixed high-Y overworld locations
 * where players build their own carriage variants. Plots are arranged along
 * +X so every variant is visible at once; the list auto-expands as custom
 * variants are registered.
 *
 * Session state (pre-enter position + dimension + look angles) is kept
 * per-player in RAM. Lost on server restart, which is acceptable for an
 * OP-only dev tool.
 */
public final class CarriageEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FIRST_PLOT_X = 0;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, GameType previousGameType) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CarriageEditor() {}

    /**
     * Record {@code player}'s current dimension + position + look + game mode
     * as the return-to state for {@code /dungeontrain editor exit}, then
     * switch the player to creative for editing. No-op if the player already
     * has a saved session, so re-entering an editor plot (even across carriage
     * and pillar editors) keeps the first entry as the anchor.
     *
     * <p>Package-private so {@link PillarEditor} can share the session map
     * without duplicating the exit plumbing.</p>
     */
    static void rememberReturn(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) return;
        GameType previous = player.gameMode.getGameModeForPlayer();
        SESSIONS.put(player.getUUID(), new Session(
            player.level().dimension(),
            player.position(),
            player.getYRot(),
            player.getXRot(),
            previous
        ));
        if (previous != GameType.CREATIVE) {
            player.setGameMode(GameType.CREATIVE);
        }
    }

    /**
     * Outcome of {@link #save} — config-dir write always happens (or throws);
     * the source-tree write is opt-in via {@link EditorDevMode} and reported
     * separately so the caller can surface partial success. Source-tree writes
     * apply to both {@link CarriageVariant.Builtin}s and
     * {@link CarriageVariant.Custom}s — a custom variant authored in the editor
     * lands in {@code src/main/resources/data/dungeontrain/templates/} alongside
     * built-ins and ships in the next build.
     */
    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    /**
     * The box a variant's plot occupies.
     *
     * <p>The box of the variant's size: a carriage for Room, the longer <b>portal corridor</b> for
     * Half (it runs past its slot into the cart between a portal's pair), a whole group for Full. The
     * plot has to be that long, or its capture would save a truncated shell.</p>
     *
     * <p><b>Only for sizing the plot box</b> — clearing it, caging it, capturing it, snapshotting it.
     * Never pass the result back into {@link CarriagePlacer#placeAt}: the placer derives the corridor
     * length from the world's carriage dims itself, and handing it an already-lengthened figure would
     * apply the growth twice.</p>
     *
     * <p>Delegates to {@link CarriagePlacer#variantDims} rather than repeating the rule — the plot,
     * the template the plot is captured into, and the sidecar bounds the plot is edited against all
     * have to be the same box, and two copies of that rule would be free to drift apart.</p>
     */
    public static CarriageDims plotDims(CarriageVariant variant, CarriageDims dims) {
        return CarriagePlacer.variantDims(variant, dims);
    }

    /**
     * The widest plot any variant can have (a Full, group-long carriage where this world can build
     * one, else the long portal corridor) plus the gap — the most one plot can move the row by, so
     * what an erase after a removal has to reach past the row's end.
     */
    private static int plotStep(CarriageDims dims) {
        int widest = ContentsSize.HALF.boxOrRoom(dims, groupSize()).length();
        widest = Math.max(widest, ContentsSize.FULL.boxOrRoom(dims, groupSize()).length());
        return widest + EditorLayout.GAP;
    }

    private static int groupSize() {
        return games.brennan.dungeontrain.config.DungeonTrainConfig.getGroupSize();
    }

    /**
     * Plot origin for {@code variant}: its pool's row ({@link CarriagePlotRows} — Rooms, Halves and
     * Groups each a row of their own), laid end to end at each plot's own saved size. Returns
     * {@code null} if the variant is not registered.
     */
    public static BlockPos plotOrigin(CarriageVariant variant, CarriageDims dims) {
        return CarriagePlotRows.originOf(variant, dims, groupSize());
    }

    /** Where {@code pool}'s row would put its next plot. */
    private static int rowEndX(CarriagePlotRows.Row pool, CarriageDims dims) {
        return CarriagePlotRows.current(dims, groupSize()).endOf(pool);
    }

    /** A plot's row and X — taken before a change moves it, so the old footprint can be cleared. */
    public record RowSpot(CarriagePlotRows.Row pool, int x) {}

    /** {@code variant}'s row and X now, or null when it has no plot. */
    public static RowSpot spotOf(CarriageVariant variant, CarriageDims dims) {
        BlockPos o = plotOrigin(variant, dims);
        return o == null ? null : new RowSpot(CarriagePlotRows.rowOf(variant), o.getX());
    }

    /** The widest box a plot in the row can be — what a slot-geometry erase clears. */
    private static CarriageDims widestBox(CarriageDims dims) {
        CarriageDims full = ContentsSize.FULL.boxOrRoom(dims, groupSize());
        CarriageDims half = ContentsSize.HALF.boxOrRoom(dims, groupSize());
        return full.length() >= half.length() ? full : half;
    }

    // ---- id → +X slot index, memoised on the registry snapshot --------------------------------

    /** The registry snapshot {@link #SLOT_INDEX} was built from; a new snapshot means a rebuild. */
    private static List<CarriageVariant> slotIndexSource;
    private static java.util.Map<String, Integer> SLOT_INDEX = java.util.Map.of();

    /**
     * Variant id → row slot. The overlay asks for every variant's origin every tick, and the
     * linear walk this replaces made each ask O(n) — O(n²) per pass. The registry hands out one
     * immutable snapshot until it mutates, so a reference compare is the whole staleness check.
     */
    private static synchronized java.util.Map<String, Integer> slotIndex() {
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        if (all == slotIndexSource) return SLOT_INDEX;
        java.util.Map<String, Integer> index = new java.util.HashMap<>(all.size() * 2);
        for (int i = 0; i < all.size(); i++) index.putIfAbsent(all.get(i).id(), i);
        SLOT_INDEX = java.util.Map.copyOf(index);
        slotIndexSource = all;
        return SLOT_INDEX;
    }

    /** How many plots the row holds right now. */
    public static int rowCount() {
        return CarriageVariantRegistry.allVariants().size();
    }

    /**
     * Returns the variant whose plot contains {@code pos} (within the
     * footprint plus 1-block outline margin), or {@code null} if none.
     */
    public static CarriageVariant plotContaining(BlockPos pos, CarriageDims dims) {
        // Answers only while CARRIAGES is the resident category — every category shares the origin.
        if (!EditorStampedCategoryState.isActive(EditorCategory.CARRIAGES)) return null;
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            BlockPos o = plotOrigin(variant, dims);
            if (o == null) continue;
            CarriageDims box = plotDims(variant, dims);
            // Y upper bound includes a couple of blocks of headroom above the
            // cage top so a player who teleported to "on top" via the new
            // landing-on-top default still counts as inPlot — same panel
            // controls (green border, action row, Enter button) stay visible.
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + box.length()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + box.height() + 2
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + box.width()) {
                return variant;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code variant}: save return
     * position, clear the footprint, stamp the current template (or fallback
     * geometry) so the player sees what would spawn today, then place the
     * barrier-block cage around the footprint.
     *
     * <p>Defaults to landing the player <b>on top</b> of the cage so they
     * can see the template from above without immediately being inside it
     * — use {@link #enter(ServerPlayer, CarriageVariant, boolean)} with
     * {@code onTop=false} to land on the floor instead (the per-plot
     * panel's "Enter" button uses that path).</p>
     */
    public static void enter(ServerPlayer player, CarriageVariant variant) {
        enter(player, variant, true);
    }

    /**
     * Always restamps: this is the reload every command and post-download jump means, whether or
     * not the player is already standing in the plot — a relay Load that replaced the file on disk
     * arrives here and must show the new blocks. The walk that keeps unsaved edits is
     * {@link #walkTo} / {@link #enterInside}.
     */
    public static void enter(ServerPlayer player, CarriageVariant variant, boolean onTop) {
        enter(player, variant, onTop, true);
    }

    /**
     * The X menu's Go here: a walk to the plot, not a reload — restamps only when the player is
     * not already standing in it. Restamping a plot you are standing in would throw away
     * every unsaved edit for the sake of a few blocks' teleport.
     */
    public static void walkTo(ServerPlayer player, CarriageVariant variant, boolean onTop) {
        enter(player, variant, onTop, !EditorPlotScope.standingIn(player, new Template.Carriage(variant)));
    }

    /**
     * The panel's Enter button: land inside at {@code inside}, restamping unless the player is
     * already standing in this plot.
     */
    public static void enterInside(ServerPlayer player, CarriageVariant variant, EditorPlotArrival.Inside inside) {
        enter(player, variant, false, !EditorPlotScope.standingIn(player, new Template.Carriage(variant)), inside);
    }

    /**
     * @param stamp whether to erase + restamp the plot before teleporting. The category entry
     *              passes {@code false}: it has just stamped this plot itself, and a second stamp
     *              would double the one synchronous cost it kept.
     */
    public static void enter(ServerPlayer player, CarriageVariant variant, boolean onTop, boolean stamp) {
        enter(player, variant, onTop, stamp, EditorPlotArrival.Inside.FRONT_DOOR);
    }

    /**
     * @param inside where an {@code onTop == false} landing aims: the -X doorway facing in, or the
     *               centre. Either way it steps to the nearest free column if that cell is built up.
     */
    public static void enter(ServerPlayer player, CarriageVariant variant, boolean onTop, boolean stamp,
                             EditorPlotArrival.Inside inside) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] Editor enter: unknown variant '{}'", variant.id());
            return;
        }

        rememberReturn(player);
        if (stamp) stampPlot(overworld, variant, dims);

        Vec3i footprint = new Template.Carriage(variant).plotSize(dims);
        BlockPos door = EditorPlotArrival.firstOrNull(CarriageDoorCells.doorBases(origin, plotDims(variant, dims)));
        EditorPlotArrival.land(player, overworld, origin, footprint, onTop, inside, door);

        LOGGER.info("[DungeonTrain] Editor enter: {} -> {} plot at {} dims={}x{}x{} ({})",
            player.getName().getString(), variant.id(), origin,
            dims.length(), dims.width(), dims.height(), onTop ? "top" : "inside");
    }

    /**
     * Erase, place, and cage the plot for {@code variant} without teleporting
     * anyone. Used by {@link #enter} and by category-wide stamps
     * ({@code /dt editor carriages}) that need every plot visible at once.
     * Idempotent — calling it twice against the same variant re-applies the
     * current stored template.
     */
    public static void stampPlot(ServerLevel overworld, CarriageVariant variant, CarriageDims dims) {
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) return;

        // Drop any stale cached sidecar so each stamp picks up manual JSON
        // edits made since the last load. Session editing then works against
        // the freshly-loaded map; `editor save` persists the result.
        CarriageVariantBlocks.invalidate(variant.id());
        // Drop in-session contents-store changes (loot-prefab links) so the
        // re-stamp reads the last-saved disk state; placement writes only
        // touch the in-memory cache until /save.
        ContainerContentsStore.invalidate("carriage:" + variant.id());

        // Box operations use the PLOT's dims (longer for the portal corridor); the placer keeps the
        // world's carriage dims and derives the corridor length itself — see plotDims.
        CarriageDims box = plotDims(variant, dims);
        CarriagePlacer.eraseAt(overworld, origin, box);
        EditorPlotEntityClearer.discardNonPlayersIn(
            overworld, origin, new Vec3i(box.length(), box.height(), box.width()));
        CarriagePlacer.placeAt(overworld, origin, variant, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, box);

        // Snapshot the freshly-stamped state so EditorDirtyCheck has a
        // baseline to compare against. The stamp pass composes base NBT +
        // parts overlay + sidecar variants — comparing live to saved NBT
        // misses the parts/sidecar contributions, so we record the actual
        // post-composition state here.
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("carriages", variant.id()),
            overworld, origin, box.length(), box.height(), box.width()
        );
    }

    /**
     * Stamp every registered carriage plot — the whole-row refresh the per-stage preview uses when
     * the focused stage (or the stage-linked content) changes. Callers gate on the CARRIAGES
     * category being stamped; see {@code EditorCommand.restampCarriagePlotsForStage}.
     */
    public static void stampAllPlots(ServerLevel overworld, CarriageDims dims) {
        // A category fill still in flight must land before a whole-kind restamp walks the same plots.
        EditorStampQueue.flush();
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            stampPlot(overworld, variant, dims);
        }
    }

    /**
     * Erase + re-stamp the dirty slice of the +X row after a variant has been removed from
     * {@link CarriageVariantRegistry}. {@code fromSlot} is the slot the variant held and
     * {@code oldRowCount} the registry size, both from <b>before</b> {@code unregister}.
     *
     * <p>Erases every slot from {@code fromSlot} through {@code oldRowCount - 1} at the widest plot
     * size — the loop works by slot and cannot know which shifted variant was a longer one, and
     * clearing extra air is harmless — then re-stamps each variant now at slot {@code fromSlot} or
     * later. Must be called <b>after</b> {@link CarriageVariantRegistry#unregister}.</p>
     */
    public static void restampRowAfterDeletion(ServerLevel level, RowSpot spot, CarriageDims dims) {
        if (spot == null) return;
        // The plots before the deleted one never moved; everything after it in its row shifted left by
        // at most one widest plot, so the old row ended no further than that past the new end.
        eraseSpan(level, spot.pool(), spot.x(), rowEndX(spot.pool(), dims) + plotStep(dims), dims);
        restampRowFrom(level, spot.pool(), spot.x(), dims);
    }

    /**
     * The insertion counterpart of {@link #restampRowAfterDeletion}: {@code id} has just been
     * <b>registered</b>, so every plot after it in its row sits further along than where its blocks
     * were stamped. Erase that slice and stamp each plot from {@code id}'s on at its new position,
     * from its saved template.
     *
     * <p>{@link #duplicate} alone stamps only the new plot, which left the rest of the row drawn over.
     * Plots in the slice lose any unsaved edits; Save-as checks for those first.</p>
     */
    public static void restampRowFrom(ServerLevel level, String id, CarriageDims dims) {
        CarriageVariant v = CarriageVariantRegistry.find(id).orElse(null);
        RowSpot spot = v == null ? null : spotOf(v, dims);
        if (spot == null) return;
        // An insertion only pushes the row further, so the new end covers the old one.
        eraseSpan(level, spot.pool(), spot.x(), rowEndX(spot.pool(), dims), dims);
        restampRowFrom(level, spot.pool(), spot.x(), dims);
    }

    /**
     * Clear every carriage row's whole span — wherever plots are now, and wherever a build that put
     * every carriage in one row spaced for the widest box ({@link #plotStep}) left them. A world
     * stamped by that build otherwise keeps those plots in the sky where no slot points any more.
     */
    public static void clearRowSpan(ServerLevel level, CarriageDims dims) {
        for (CarriagePlotRows.Row pool : CarriagePlotRows.Row.values()) {
            eraseSpan(level, pool, FIRST_PLOT_X, rowSpanEndX(pool, dims), dims);
        }
    }

    /** The box {@link #clearRowSpan} clears, for the stamp queue's ordering. */
    public static net.minecraft.world.level.levelgen.structure.BoundingBox rowSpanBox(CarriageDims dims) {
        CarriageDims box = widestBox(dims);
        int end = FIRST_PLOT_X;
        for (CarriagePlotRows.Row pool : CarriagePlotRows.Row.values()) end = Math.max(end, rowSpanEndX(pool, dims));
        int zMin = CarriagePlotRows.rowZ(CarriagePlotRows.Row.PORTALS);   // the furthest row toward -Z
        return EditorLayerSweep.plotBox(new BlockPos(FIRST_PLOT_X, EditorLayout.PLOT_Y, zMin),
            new Vec3i(end - FIRST_PLOT_X, box.height(), CarriagePlotRows.rowZ(CarriagePlotRows.Row.ROOMS) - zMin + box.width()));
    }

    private static int rowSpanEndX(CarriagePlotRows.Row pool, CarriageDims dims) {
        int end = rowEndX(pool, dims) + plotStep(dims);
        // The Room row is also where the single widest-spaced row of older builds stood.
        if (pool == CarriagePlotRows.Row.ROOMS) end = Math.max(end, FIRST_PLOT_X + rowCount() * plotStep(dims));
        return end;
    }

    /**
     * Clear {@code pool}'s row from {@code fromX} up to {@code toX} — every plot and cage in it, and
     * nothing in any other row. Plots are their own lengths, so after a size or row change the old
     * ones no longer sit where the slots now say; clearing the span rather than slot by slot reaches
     * wherever they were.
     */
    private static void eraseSpan(ServerLevel level, CarriagePlotRows.Row pool, int fromX, int toX, CarriageDims dims) {
        BlockState air = Blocks.AIR.defaultBlockState();
        CarriageDims box = widestBox(dims);
        int rowZ = CarriagePlotRows.rowZ(pool);
        int y0 = EditorLayout.PLOT_Y - 1;
        for (int x = fromX - 1; x <= toX; x++) {
            for (int y = y0; y <= EditorLayout.PLOT_Y + box.height(); y++) {
                for (int z = rowZ - 1; z <= rowZ + box.width(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) level.setBlock(pos, air, 3);
                }
            }
        }
    }

    /** Stamp every plot in {@code pool}'s row from {@code fromX} on. */
    private static void restampRowFrom(ServerLevel level, CarriagePlotRows.Row pool, int fromX, CarriageDims dims) {
        for (CarriageVariant v : CarriagePlotRows.membersOf(pool)) {
            BlockPos o = plotOrigin(v, dims);
            if (o != null && o.getX() >= fromX) stampPlot(level, v, dims);
        }
    }

    /** The row slot {@code id} occupies, or -1 when it is not registered. */
    public static int slotOf(String id) {
        Integer index = slotIndex().get(id);
        return index == null ? -1 : index;
    }

    /**
     * Erase the plot for {@code variant} — footprint cleared to air and the
     * barrier cage around it removed. Used when switching categories (leaves
     * no stale carriages visible once the player moves on to tracks) and on
     * {@code /dt editor exit} (tidy the sky-plots when nobody is editing).
     */
    public static void clearPlot(ServerLevel overworld, CarriageVariant variant, CarriageDims dims) {
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) return;
        CarriageDims box = plotDims(variant, dims);
        CarriagePlacer.eraseAt(overworld, origin, box);
        setOutline(overworld, origin, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), box);
        // Drop the dirty-check baseline — the plot is no longer stamped, so
        // comparing a future live read (all air) to a stamped snapshot would
        // report every carriage as dirty when the player switches categories.
        EditorPlotSnapshots.clear(EditorPlotSnapshots.key("carriages", variant.id()));
    }

    /**
     * Capture the {@code length × height × width} region at the plot for
     * {@code variant} into a fresh {@link StructureTemplate} and persist it
     * via {@link CarriageTemplateStore}. Air positions are excluded so the
     * saved template only describes placed blocks. When {@link EditorDevMode}
     * is on, the same template is also written through to the source tree so
     * it ships with the next mod build — applies to built-ins AND customs
     * (custom variants get a slot in the source tree the first time they save
     * in dev mode, which then unlocks the cascading sidecar promotes in
     * {@link CarriageVariantPartsStore} and
     * {@link CarriageVariantContentsAllowStore}).
     */
    public static SaveResult save(ServerPlayer player, CarriageVariant variant) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) throw new IOException("Unknown variant '" + variant.id() + "'.");

        // The plot's own box — longer than a carriage for the portal corridor, so the capture below
        // saves the whole corridor rather than its first nine blocks, and the sidecar keeps entries
        // authored in the part of the plot that is past a carriage's length.
        CarriageDims box = plotDims(variant, dims);
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, box);

        StructureTemplate template = captureTemplate(overworld, origin, box);
        CarriageTemplateStore.save(variant, template);

        // Variant sidecar: snapshot whatever the in-memory cache holds (the
        // `editor variant set/clear` commands mutate it eagerly during the
        // session). Empty maps are written as a deleted file so removing every
        // entry doesn't leave a stale sidecar on disk.
        sidecar.save(variant);

        // Contents store: persist any in-session loot-prefab link changes
        // accumulated since enter (PrefabUseHandler defers its writes until
        // /save). Failure is logged but doesn't fail the whole save.
        try {
            ContainerContentsStore.loadFor("carriage:" + variant.id()).save();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Editor save: contents-store save failed for {}: {}",
                variant.id(), e.toString());
        }

        // Refresh the dirty-check baseline so the just-saved state reads as
        // clean on the next /dt editor unsaved-list query.
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("carriages", variant.id()),
            overworld, origin, box.length(), box.height(), box.width()
        );

        // …and, when they have opted in, to the player's relay profile. Hooked here rather than at
        // the callers because both ways in land on this method — see EditorRelaySave.
        EditorRelaySave.afterSave(player, new Template.Carriage(variant));
        LOGGER.info("[DungeonTrain] Editor save: {} -> {} template dims={}x{}x{} ({} variant entries)",
            player.getName().getString(), variant.id(), box.length(), box.width(), box.height(),
            sidecar.size());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriageTemplateStore.saveToSource(variant, template);
            sidecar.saveToSource(variant);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Editor save: source write failed for {}: {}", variant.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Create a new custom variant {@code target} whose template is a duplicate
     * of {@code source}'s current geometry. Registers the variant so it
     * immediately shows up in {@link CarriageVariantRegistry} and gets its own
     * plot on subsequent lookups.
     *
     * <p>Fails if {@code source} resolves to no template (i.e. a custom with a
     * missing file). Built-in sources always succeed because they fall back
     * through the three-tier store to bundled or hardcoded geometry.
     */
    /**
     * Create a brand-new custom variant {@code target} with an empty plot —
     * registered, allocated, caged, but with no template stamped. The author
     * builds the carriage from scratch on the bedrock floor.
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageVariant.Custom target) throws IOException {
        return createBlank(player, target, ContentsSize.ROOM);
    }

    /**
     * As above, at {@code size}. The size is declared before the variant is registered, so its
     * first plot lookup already lands it in its own size's row and its capture is that size's box.
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageVariant.Custom target,
                                       ContentsSize size) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        CarriageDims box = size.shellDims(dims, groupSize()).orElseThrow(() -> new IOException(
            "A " + size.key() + " carriage is longer than this world's carriages can be."));

        if (CarriageVariantRegistry.find(target.id()).isPresent()) {
            throw new IOException("Variant '" + target.id() + "' is already registered.");
        }
        // The pool is where the template is saved, so it is chosen before anything is written.
        games.brennan.dungeontrain.train.ShellPool.set(target.id(), games.brennan.dungeontrain.train.ShellPool.of(size));
        if (!CarriageVariantRegistry.register(target)) {
            games.brennan.dungeontrain.train.ShellPool.forget(target.id());
            throw new IOException("Variant '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageVariantRegistry.unregister(target.id());
            games.brennan.dungeontrain.train.ShellPool.forget(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        CarriagePlacer.eraseAt(overworld, targetOrigin, box);

        StructureTemplate template = captureTemplate(overworld, targetOrigin, box);
        CarriageTemplateStore.save(target, template);

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, box);

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_carriage");
        LOGGER.info("[DungeonTrain] Editor createBlank: {} created '{}' at {}",
            player.getName().getString(), target.id(), targetOrigin);
        return targetOrigin;
    }

    public static BlockPos duplicate(ServerPlayer player, CarriageVariant source, CarriageVariant.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // A copy is its source's length, so it lives in its source's pool.
        games.brennan.dungeontrain.train.ShellPool.set(target.id(), games.brennan.dungeontrain.train.ShellPool.of(CarriagePlacer.sizeOf(source)));
        if (!CarriageVariantRegistry.register(target)) {
            throw new IOException("Variant '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageVariantRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        CarriageDims box = plotDims(source, dims);
        CarriagePlacer.eraseAt(overworld, targetOrigin, box);
        CarriagePlacer.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = captureTemplate(overworld, targetOrigin, box);
        if (template.getSize().equals(Vec3i.ZERO)) {
            CarriageVariantRegistry.unregister(target.id());
            throw new IOException("Source '" + source.id() + "' produced no geometry.");
        }
        CarriageTemplateStore.save(target, template);

        // Everything beside the .nbt goes with it — the variant sidecar ("pick from these blocks"
        // authoring, lock-ids, mirror flags), the part assignments, the contents allow-list, the
        // container links and the weights entry — so the duplicate is the source, not just its shape.
        TemplateCopy.copy(games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.CARRIAGE, null,
            source.id(), target.id());

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, box);

        LOGGER.info("[DungeonTrain] Editor duplicate: {} created '{}' from '{}' at {}",
            player.getName().getString(), target.id(), source.id(), targetOrigin);
        return targetOrigin;
    }

    /**
     * Change {@code variant}'s size — Room, Half, or Group ({@link ContentsSize#FULL}) — keeping its
     * saved blocks. The saved template is re-lengthed ({@link TemplateLength}) and moved, with its
     * sidecars, into that size's pool ({@link ShellPoolMove}): growing pads the far end with air,
     * shrinking crops it.
     *
     * <p>Works from the <b>saved</b> template, not the live plot, so a plot with unsaved edits is
     * refused rather than having them silently dropped or baked in.</p>
     *
     * @return the new box
     */
    public static CarriageDims resize(ServerPlayer player, CarriageVariant variant, ContentsSize size) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        if (games.brennan.dungeontrain.portal.PortalCarriageBuilder.isPortalVariant(variant)) {
            throw new IOException("Portal carriages keep the size the portal needs.");
        }
        if (CarriagePlotRows.rowOf(variant) == CarriagePlotRows.Row.FLATBEDS) {
            throw new IOException("The flatbed keeps its size — the pads between groups are cut from it.");
        }
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) throw new IOException("Unknown variant '" + variant.id() + "'.");
        ContentsSize from = CarriagePlacer.sizeOf(variant);
        CarriageDims oldBox = plotDims(variant, dims);
        CarriageDims newBox = size.shellDims(dims, groupSize()).orElseThrow(() -> new IOException(
            "A " + size.key() + " carriage is longer than this world's carriages can be."));
        if (from == size) return newBox;
        // The plot leaves its row and joins another: every plot after it in the old row slides back,
        // every plot after where it lands in the new row slides on, and all of those are restamped
        // from their saved templates — so none of them may be holding unsaved edits either.
        games.brennan.dungeontrain.train.ShellPool toPool = games.brennan.dungeontrain.train.ShellPool.of(size);
        RowSpot oldSpot = spotOf(variant, dims);
        refuseUnsaved(overworld, dims, variant, oldSpot, CarriagePlotRows.Row.of(toPool));
        StructureTemplate saved = CarriageTemplateStore.get(overworld, variant, oldBox).orElseThrow(() ->
            new IOException("'" + variant.id() + "' has no saved template to resize."));
        // Re-length the template itself rather than stamping and re-capturing: every category
        // shares the plot origin, so the world there may be showing something else.
        StructureTemplate resized = TemplateLength.withLength(saved, newBox.length(),
            overworld.holderLookup(net.minecraft.core.registries.Registries.BLOCK));

        boolean shown = EditorStampedCategoryState.isActive(EditorCategory.CARRIAGES);
        int oldEnd = oldSpot == null ? FIRST_PLOT_X : rowEndX(oldSpot.pool(), dims);
        int newRowOldEnd = rowEndX(CarriagePlotRows.Row.of(toPool), dims);
        try {
            // Each size is its own pool of templates, stored apart: the switch moves it there.
            ShellPoolMove.move(variant, toPool, resized, EditorDevMode.isEnabled());
        } finally {
            if (shown) {
                // The old row closes the gap; the new row opens one where the plot lands.
                if (oldSpot != null) {
                    eraseSpan(overworld, oldSpot.pool(), oldSpot.x(), Math.max(oldEnd, rowEndX(oldSpot.pool(), dims)), dims);
                    restampRowFrom(overworld, oldSpot.pool(), oldSpot.x(), dims);
                }
                RowSpot newSpot = spotOf(variant, dims);
                if (newSpot != null) {
                    eraseSpan(overworld, newSpot.pool(), newSpot.x(), Math.max(newRowOldEnd, rowEndX(newSpot.pool(), dims)), dims);
                    restampRowFrom(overworld, newSpot.pool(), newSpot.x(), dims);
                }
            }
        }
        LOGGER.info("[DungeonTrain] Editor resize: {} resized '{}' {} -> {} ({} long)",
            player.getName().getString(), variant.id(), from.key(), size.key(), newBox.length());
        return newBox;
    }

    /**
     * Refuse a move when a plot it would restamp holds unsaved edits: this one, the plots after it in
     * its row, and the plots that come after it in registry order in the row it moves to.
     */
    private static void refuseUnsaved(ServerLevel overworld, CarriageDims dims, CarriageVariant variant,
                                      RowSpot oldSpot, CarriagePlotRows.Row toRow) throws IOException {
        java.util.Set<String> unsaved = EditorDirtyCheck.unsavedModelIds(overworld, dims, "carriages");
        if (unsaved.isEmpty()) return;
        int at = slotOf(variant.id());
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        for (int i = 0; i < all.size(); i++) {
            CarriageVariant v = all.get(i);
            if (!unsaved.contains(v.id())) continue;
            boolean movesInOldRow = oldSpot != null && CarriagePlotRows.rowOf(v) == oldSpot.pool()
                && plotOrigin(v, dims) != null && plotOrigin(v, dims).getX() >= oldSpot.x();
            boolean movesInNewRow = CarriagePlotRows.rowOf(v) == toRow && i > at;
            if (v.id().equals(variant.id()) || movesInOldRow || movesInNewRow) {
                throw new IOException("'" + v.id() + "' has unsaved edits — save or reset it first.");
            }
        }
    }

    /**
     * Save the plot's current geometry under a new name. Follows the
     * rename-on-save rules documented in {@code EditorCommand}: protected
     * built-ins ({@code standard}, {@code flatbed}) cannot be renamed; other
     * built-ins revert to hardcoded fallback and the edited geometry is saved
     * as a new custom; custom sources are moved to the new name.
     *
     * <p>Returns the new variant identifier.
     */
    public static CarriageVariant.Custom saveAs(ServerPlayer player, CarriageVariant current, CarriageVariant.Custom renamed) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(current, dims);
        if (origin == null) throw new IOException("Unknown variant '" + current.id() + "'.");

        StructureTemplate template = captureTemplate(overworld, origin, plotDims(current, dims));

        // The renamed variant is the same length, so it keeps its size (and its row).
        games.brennan.dungeontrain.train.ShellPool.set(renamed.id(), games.brennan.dungeontrain.train.ShellPool.of(CarriagePlacer.sizeOf(current)));
        if (current instanceof CarriageVariant.Custom currentCustom) {
            if (!CarriageVariantRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageTemplateStore.save(renamed, template);
            CarriageVariantBlocks.rename(currentCustom.name(), renamed.id());
            CarriageVariantRegistry.unregister(currentCustom.name());
            CarriageTemplateStore.delete(currentCustom);
            CarriageVariantBlocks.invalidate(currentCustom.name());
            TemplateSizeStore.SHELLS.forget(currentCustom.name());
            games.brennan.dungeontrain.train.ShellPool.forget(currentCustom.name());
            LOGGER.info("[DungeonTrain] Editor saveAs (custom→custom): {} renamed '{}' -> '{}'",
                player.getName().getString(), currentCustom.name(), renamed.id());
        } else if (current instanceof CarriageVariant.Builtin builtin) {
            if (!CarriageVariantRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageTemplateStore.save(renamed, template);
            CarriageVariantBlocks.rename(builtin.id(), renamed.id());
            CarriageTemplateStore.delete(builtin);
            CarriageVariantBlocks.invalidate(builtin.id());
            LOGGER.info("[DungeonTrain] Editor saveAs (builtin→custom): {} saved edits of '{}' as new custom '{}', built-in reverts to fallback",
                player.getName().getString(), builtin.id(), renamed.id());
        }

        return renamed;
    }

    /** Restore player to pre-enter position/dimension/game mode. Returns false if no session. */
    public static boolean exit(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel dim = server.getLevel(session.dimension());
        if (dim == null) return false;
        player.teleportTo(dim, session.pos().x, session.pos().y, session.pos().z,
            session.yaw(), session.pitch());
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        VariantOverlayRenderer.forget(player);
        return true;
    }

    /**
     * Capture a carriage-sized region as a template.
     *
     * <p>Public so the Train Builder's save writes through the same capture the editor does —
     * two copies of this would be two things to keep in step.</p>
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriageDims dims) {
        Vec3i size = new Vec3i(dims.length(), dims.height(), dims.width());
        return TemplateDecor.capture(level, origin, size, Blocks.AIR);
    }

    /** The plot cage — see {@link EditorPlotCage}. */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state, CarriageDims dims) {
        EditorPlotCage.setOutline(level, origin, new Vec3i(dims.length(), dims.height(), dims.width()), state);
    }
}
