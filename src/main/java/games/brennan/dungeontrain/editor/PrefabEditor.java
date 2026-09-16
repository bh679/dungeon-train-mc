package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The editor for prefabs — designs of any size that other templates place via a prefab anchor.
 *
 * <p>Modelled on {@link PortalRoomEditor}, the other free-sized kind, and deliberately leaner: a
 * prefab has no doorways, no corridor mask, no copies and no room settings. What it keeps is the
 * part that a variable size forces — plots laid out from each prefab's own size, the world
 * remembering where each plot actually stands ({@link DungeonTrainWorldData#prefabPlotBoxes}) so
 * a plot that moved when a neighbour grew is erased where it is, and a reset that puts the size
 * back as well as the blocks.</p>
 *
 * <p>A prefab plot is captured with {@code STRUCTURE_VOID} as the ignored block: a void cell is
 * "leave whatever the parent has there", an air cell carves. Anchors placed inside a prefab are
 * saved as blocks like any other and stay visible in the plot — the editor never resolves them.</p>
 */
public final class PrefabEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch,
                          GameType previousGameType) {}

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private record PlotBox(BlockPos origin, Vec3i size) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private PrefabEditor() {}

    /** Every registered prefab name, {@code default} first. */
    public static List<String> names() {
        return TrackVariantRegistry.namesFor(TrackKind.PREFAB);
    }

    public static BlockPos plotOrigin(String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(TrackKind.PREFAB, name, dims);
    }

    public static Vec3i plotSize(String name) {
        return PrefabSizes.sizeOf(name);
    }

    /**
     * The prefab whose plot contains {@code pos}, or null. Includes the 1-block cage margin and the
     * +2 Y headroom every other plot uses for a player who landed on the cage.
     */
    @Nullable
    public static String plotContaining(BlockPos pos, CarriageDims dims) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.PREFABS)) return null;
        for (String name : names()) {
            BlockPos o = plotOrigin(name, dims);
            Vec3i size = plotSize(name);
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + size.getX()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + size.getY() + 2
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + size.getZ()) {
                return name;
            }
        }
        return null;
    }

    /** Teleport to {@code name}'s plot, stamping every prefab plot first. */
    public static void enter(ServerPlayer player, String name) {
        enter(player, name, true, true);
    }

    /**
     * @param stamp whether to erase + restamp every plot before teleporting. The category entry
     *              passes {@code false}: it stamps the landing plot itself and queues the rest.
     */
    public static void enter(ServerPlayer player, String name, boolean onTop, boolean stamp) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        primeSizes(overworld, dims);
        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name);

        if (!SESSIONS.containsKey(player.getUUID())) {
            GameType previous = player.gameMode.getGameModeForPlayer();
            SESSIONS.put(player.getUUID(), new Session(
                player.level().dimension(), player.position(), player.getYRot(), player.getXRot(), previous));
            if (previous != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);
        }

        if (stamp) stampAllPlots(overworld, dims);

        double tx = origin.getX() + size.getX() / 2.0;
        double ty = onTop ? origin.getY() + size.getY() + 1.0 : origin.getY() + 1.0;
        double tz = origin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        player.sendSystemMessage(Component.literal(
            "[DungeonTrain] Prefab editor: build a design here and place it in other templates with a "
            + "Prefab Anchor (right-click the anchor to bind it; /dt editor prefabs anchor <name> gives "
            + "a bound one). The design grows from the anchor along the arrow: this plot's +X is forward. "
            + "Structure void = keep the parent's block; air = carve. Resize with "
            + "/dt editor prefabs size <L> <H> <W>."));

        LOGGER.info("[DungeonTrain] Editor enter: {} -> prefab '{}' plot at {} ({}x{}x{}, {} prefabs)",
            player.getName().getString(), name, origin, size.getX(), size.getY(), size.getZ(), names().size());
    }

    /** Load every registered prefab once so {@link PrefabSizes} knows how big each one is. */
    public static void primeSizes(ServerLevel overworld, CarriageDims dims) {
        for (String name : names()) {
            PrefabTemplateStore.get(overworld, name, dims);
        }
    }

    /** Erase + restamp every registered prefab plot. Idempotent. */
    public static void stampAllPlots(ServerLevel overworld, CarriageDims dims) {
        EditorStampQueue.flush();
        primeSizes(overworld, dims);
        for (String name : names()) {
            stampPlot(overworld, name, dims);
        }
    }

    /** Erase + restamp the single plot for {@code name} — the saved template, or an empty box. */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        // Every size first: where this plot goes depends on the prefabs before it in the row.
        primeSizes(overworld, dims);
        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name);

        // Standing somewhere else from an earlier layout? Erase it there first.
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        int[] was = data.prefabPlotBox(name);
        if (was != null && !sameBox(was, origin, size)) {
            clearBox(overworld, new PlotBox(new BlockPos(was[0], was[1], was[2]),
                new Vec3i(was[3], was[4], was[5])), name);
        }

        LOGGER.info("[DungeonTrain] Prefab plot '{}' at {} size {}x{}x{}", name, origin.toShortString(),
            size.getX(), size.getY(), size.getZ());
        clearBox(overworld, new PlotBox(origin, size), name);
        stampPrefabInto(overworld, origin, name, dims);
        setOutline(overworld, origin, size, OUTLINE_BLOCK);
        captureSnapshot(overworld, origin, size, name);
    }

    /**
     * Stamp {@code name}'s saved design at {@code origin} — the raw template, relit, anchors and
     * all. Nothing when no template has been saved yet: a new prefab opens as an empty box.
     */
    public static void stampPrefabInto(ServerLevel level, BlockPos origin, String name, CarriageDims dims) {
        Vec3i size = plotSize(name);
        EditorPlotEntityClearer.discardNonPlayersIn(level, origin, size);
        Optional<StructureTemplate> template = PrefabTemplateStore.get(level, name, dims);
        if (template.isEmpty()) return;
        CarriagePlacer.stampTemplateClippedAt(level, origin, template.get(), boxOf(origin, size), /*relight*/ true);
    }

    /**
     * Reset: back to the last saved template, at the last saved <b>size</b>. Dropping the pending
     * size override is what makes Reset mean the same thing here as on every fixed-size plot.
     */
    public static void resetToSaved(ServerLevel overworld, String name, CarriageDims dims) {
        Vec3i before = plotSize(name);
        EditorPlotSnapshots.clear(snapshotKey(name));
        relayout(overworld, dims, () -> PrefabSizes.revert(name));
        stampPlot(overworld, name, dims);
        Vec3i after = plotSize(name);
        if (!before.equals(after)) {
            LOGGER.info("[DungeonTrain] Prefab '{}' reset — size back to {}x{}x{} from {}x{}x{}",
                name, after.getX(), after.getY(), after.getZ(), before.getX(), before.getY(), before.getZ());
        }
    }

    /** Empty {@code name}'s plot inside its cage. Nothing on disk changes; the snapshot is kept so it reads dirty. */
    public static void clearToEmpty(ServerLevel overworld, String name, CarriageDims dims) {
        PrefabTemplateStore.get(overworld, name, dims);
        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name);
        EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, size);
        PortalClear.clearBoxRelit(overworld, boxOf(origin, size), PortalCorridorMask.NONE);
    }

    /** Erase every prefab plot — at the recorded box where the world has one, the predicted box otherwise. */
    public static void clearAllPlots(ServerLevel overworld, CarriageDims dims) {
        primeSizes(overworld, dims);
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        Map<String, int[]> recorded = data.prefabPlotBoxes();
        for (Map.Entry<String, int[]> e : recorded.entrySet()) {
            int[] b = e.getValue();
            clearBox(overworld, new PlotBox(new BlockPos(b[0], b[1], b[2]), new Vec3i(b[3], b[4], b[5])), e.getKey());
        }
        for (String name : names()) {
            BlockPos origin = plotOrigin(name, dims);
            Vec3i size = plotSize(name);
            int[] b = recorded.get(name);
            if (b != null && sameBox(b, origin, size)) continue;
            clearBox(overworld, new PlotBox(origin, size), name);
        }
    }

    /** One box around every prefab plot, recorded and predicted, or null when there are none. */
    @Nullable
    public static BoundingBox allPlotsBox(ServerLevel overworld, CarriageDims dims) {
        primeSizes(overworld, dims);
        List<BoundingBox> boxes = new ArrayList<>();
        for (int[] b : DungeonTrainWorldData.get(overworld).prefabPlotBoxes().values()) {
            boxes.add(EditorLayerSweep.plotBox(new BlockPos(b[0], b[1], b[2]), new Vec3i(b[3], b[4], b[5])));
        }
        for (String name : names()) {
            boxes.add(EditorLayerSweep.plotBox(plotOrigin(name, dims), plotSize(name)));
        }
        return EditorLayerSweep.unionOf(boxes);
    }

    /** Erase a single prefab plot — interior + cage cleared to air. */
    public static void clearPlot(ServerLevel overworld, String name, CarriageDims dims) {
        PrefabTemplateStore.get(overworld, name, dims);
        clearBox(overworld, new PlotBox(plotOrigin(name, dims), plotSize(name)), name);
    }

    private static void clearBox(ServerLevel overworld, PlotBox box, String name) {
        // Through PortalClear: a plot being erased may hold authored chests, and replacing a
        // container that still has its block entity spills its contents as items.
        PortalClear.clearBoxRelit(overworld, boxOf(box.origin(), box.size()), PortalCorridorMask.NONE);
        setOutline(overworld, box.origin(), box.size(), Blocks.AIR.defaultBlockState());
        EditorPlotSnapshots.clear(snapshotKey(name));
        DungeonTrainWorldData.get(overworld).forgetPrefabPlotBox(name);
    }

    /**
     * Create a new prefab of {@code size}: an empty design saved to disk straight away, so the
     * registry's next scan finds the name and the plot survives a restart.
     */
    public static void createNew(ServerLevel overworld, String name, Vec3i size, CarriageDims dims)
            throws IOException {
        StructureTemplate blank = emptyOfSize(overworld, size);
        try {
            relayout(overworld, dims, () -> {
                try {
                    PrefabTemplateStore.save(name, blank);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                TrackVariantRegistry.register(TrackKind.PREFAB, name);
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    /** A template of {@code size} with no blocks in it — captured from an all-void volume in the void. */
    private static StructureTemplate emptyOfSize(ServerLevel overworld, Vec3i size) {
        // fillFromWorld with the ignored block equal to what the volume holds records nothing but the
        // size. Well above the build height every plot uses, and read-only: no block is written.
        BlockPos far = new BlockPos(0, overworld.getMaxBuildHeight() - size.getY() - 1, 0);
        StructureTemplate t = new StructureTemplate();
        t.fillFromWorld(overworld, far, size, /*includeEntities*/ false, Blocks.AIR);
        return t;
    }

    /**
     * Resize {@code name}'s plot. The live blocks are carried across (clipped at the new edge);
     * the whole row is re-laid because the plots after it may have to move.
     */
    public static Vec3i setSize(ServerLevel overworld, String name, Vec3i wanted, CarriageDims dims) {
        Vec3i size = new Vec3i(Math.max(1, wanted.getX()), Math.max(1, wanted.getY()), Math.max(1, wanted.getZ()));
        BlockPos oldOrigin = plotOrigin(name, dims);
        Vec3i oldSize = plotSize(name);
        StructureTemplate live = EditorPlotSnapshots.has(snapshotKey(name))
            ? TemplateDecor.capture(overworld, oldOrigin, oldSize, Blocks.STRUCTURE_VOID)
            : null;
        relayout(overworld, dims, () -> PrefabSizes.pending(name, size));
        if (live != null) {
            BlockPos origin = plotOrigin(name, dims);
            CarriagePlacer.stampTemplateClippedAt(overworld, origin, live, boxOf(origin, size), /*relight*/ true);
            setOutline(overworld, origin, size, OUTLINE_BLOCK);
            captureSnapshot(overworld, origin, size, name);
        }
        return size;
    }

    /**
     * Run {@code change} — something that moves plots: a size, a new or deleted name — with every
     * plot erased before and restamped after. Plots keep their live (unsaved) blocks across the
     * move, except the one whose snapshot has been cleared (a reset).
     */
    public static void relayout(ServerLevel overworld, CarriageDims dims, Runnable change) {
        primeSizes(overworld, dims);
        Map<String, StructureTemplate> live = new HashMap<>();
        for (String name : names()) {
            if (!EditorPlotSnapshots.has(snapshotKey(name))) continue;
            live.put(name, TemplateDecor.capture(overworld, plotOrigin(name, dims), plotSize(name), Blocks.STRUCTURE_VOID));
        }
        clearAllPlots(overworld, dims);
        change.run();
        primeSizes(overworld, dims);
        for (String name : names()) {
            BlockPos origin = plotOrigin(name, dims);
            Vec3i size = plotSize(name);
            StructureTemplate carried = live.get(name);
            if (carried != null) {
                EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, size);
                CarriagePlacer.stampTemplateClippedAt(overworld, origin, carried, boxOf(origin, size), /*relight*/ true);
            } else {
                stampPrefabInto(overworld, origin, name, dims);
            }
            setOutline(overworld, origin, size, OUTLINE_BLOCK);
            captureSnapshot(overworld, origin, size, name);
        }
    }

    private static void captureSnapshot(ServerLevel overworld, BlockPos origin, Vec3i size, String name) {
        EditorPlotSnapshots.capture(snapshotKey(name), overworld, origin, size.getX(), size.getY(), size.getZ());
        DungeonTrainWorldData.get(overworld).recordPrefabPlotBox(name,
            origin.getX(), origin.getY(), origin.getZ(), size.getX(), size.getY(), size.getZ());
    }

    /** Snapshot key shared with {@link EditorDirtyCheck}. */
    public static String snapshotKey(String name) {
        return EditorPlotSnapshots.key("prefabs", "prefab:" + name);
    }

    /**
     * Capture the plot as {@code name}'s template. In {@link EditorDevMode} also writes into the
     * source tree so authored prefabs ship with the next build.
     */
    public static SaveResult save(ServerPlayer player, String name) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name);

        SaveResult result = saveFrom(overworld, origin, size, name);
        captureSnapshot(overworld, origin, size, name);
        EditorRelaySave.afterSave(player, new Template.Prefab(name));
        LOGGER.info("[DungeonTrain] Editor save: {} -> prefab '{}' template ({}x{}x{})",
            player.getName().getString(), name, size.getX(), size.getY(), size.getZ());
        return result;
    }

    /** The disk-writing half of {@link #save}. */
    public static SaveResult saveFrom(ServerLevel level, BlockPos origin, Vec3i size, String name) throws IOException {
        StructureTemplate template = TemplateDecor.capture(level, origin, size, Blocks.STRUCTURE_VOID);
        PrefabTemplateStore.save(name, template);
        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            PrefabTemplateStore.saveToSource(name, template);
            try {
                TrackVariantBlocks.loadFor(TrackKind.PREFAB, name, size).saveToSource(TrackKind.PREFAB, name);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Prefab save: variant sidecar source write failed for {}: {}",
                    name, e.toString());
            }
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Prefab save: source write failed for {}: {}", name, e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /** Restore the player to their pre-enter position / game mode. False when no session. */
    public static boolean exit(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel dim = server.getLevel(session.dimension());
        if (dim == null) return false;
        player.teleportTo(dim, session.pos().x, session.pos().y, session.pos().z, session.yaw(), session.pitch());
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        return true;
    }

    private static boolean sameBox(int[] was, BlockPos origin, Vec3i size) {
        return was[0] == origin.getX() && was[1] == origin.getY() && was[2] == origin.getZ()
            && was[3] == size.getX() && was[4] == size.getY() && was[5] == size.getZ();
    }

    private static BoundingBox boxOf(BlockPos origin, Vec3i size) {
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1, origin.getY() + size.getY() - 1, origin.getZ() + size.getZ() - 1);
    }

    /** Draw the bedrock cage along the 12 edges of the plot. */
    private static void setOutline(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        CarriageStampGuard.run(() -> {
            int x0 = origin.getX() - 1, y0 = origin.getY() - 1, z0 = origin.getZ() - 1;
            int x1 = origin.getX() + size.getX(), y1 = origin.getY() + size.getY(), z1 = origin.getZ() + size.getZ();
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        int extremes = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0)
                            + (z == z0 || z == z1 ? 1 : 0);
                        if (extremes < 2) continue;
                        level.setBlock(new BlockPos(x, y, z), state, 3);
                    }
                }
            }
        });
    }
}
