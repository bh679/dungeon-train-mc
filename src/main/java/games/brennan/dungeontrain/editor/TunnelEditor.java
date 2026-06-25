package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.LegacyTunnelPaint;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-plot editor for tunnel kinds. Layout follows
 * {@link TrackSidePlots}: SECTION at the {@link TrackSidePlots#X_TUNNELS}
 * column, PORTAL stacked above SECTION on Y; each kind's registered
 * variant names lay out along {@code +Z} with
 * {@link EditorLayout#GAP}-block spacing.
 *
 * <p>Pre-enter session state is per-player; on exit the dispatcher tries
 * {@link #exit} first, falling back to {@link CarriageEditor#exit}.</p>
 */
public final class TunnelEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, GameType previousGameType) {}

    /** Resolved variant + name pair for a player position. */
    public record TunnelPlot(TunnelVariant variant, String name) {}

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private TunnelEditor() {}

    /** Plot origin for {@code (variant, default)}. */
    public static BlockPos plotOrigin(TunnelVariant variant) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackSidePlots.plotOriginDefault(TunnelTemplateStore.tunnelKind(variant), dims);
    }

    /** Plot origin for an id-record-shaped tunnel template. Tunnel dims are fixed; CarriageDims is computed internally. */
    public static BlockPos plotOrigin(games.brennan.dungeontrain.template.TunnelTemplateId id) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackSidePlots.plotOrigin(TunnelTemplateStore.tunnelKind(id.variant()), id.name(), dims);
    }

    /** Plot origin for {@code (variant, name)} — bare-tuple wrapper over the id-record form. */
    public static BlockPos plotOrigin(TunnelVariant variant, String name) {
        return plotOrigin(new games.brennan.dungeontrain.template.TunnelTemplateId(variant, name));
    }

    /** Returns the variant whose plot contains {@code pos}, or null. Legacy entry point. */
    public static TunnelVariant plotContaining(BlockPos pos) {
        TunnelPlot loc = plotContainingNamed(pos);
        return loc != null ? loc.variant() : null;
    }

    /**
     * Returns the {@code (variant, name)} pair whose plot contains
     * {@code pos}, or null. Includes the 1-block outline-cage margin.
     */
    public static TunnelPlot plotContainingNamed(BlockPos pos) {
        for (TunnelVariant variant : TunnelVariant.values()) {
            for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
                BlockPos o = plotOrigin(variant, name);
                // +2 Y headroom — see CarriageEditor.plotContaining.
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + TunnelPlacer.LENGTH
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + TunnelPlacer.HEIGHT + 2
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + TunnelPlacer.WIDTH) {
                    return new TunnelPlot(variant, name);
                }
            }
        }
        return null;
    }

    /** Teleport to the default plot for {@code variant}, stamping every variant first. */
    public static void enter(ServerPlayer player, TunnelVariant variant) {
        enter(player, variant, true);
    }

    public static void enter(ServerPlayer player, TunnelVariant variant, boolean onTop) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        BlockPos origin = plotOrigin(variant, TrackKind.DEFAULT_NAME);

        if (!SESSIONS.containsKey(player.getUUID())) {
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

        stampPlot(overworld, variant);

        double tx = origin.getX() + TunnelPlacer.LENGTH / 2.0;
        double ty = onTop
            ? origin.getY() + TunnelPlacer.HEIGHT + 1.0
            : origin.getY() + 1.0;
        double tz = origin.getZ() + TunnelPlacer.WIDTH / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        // The rest is rebuilt by mirroring on save — edit the low master quarter only.
        player.sendSystemMessage(Component.literal(
            "[DungeonTrain] Tunnel editor: edit the low master quarter; enabled mirror axes "
            + "rebuild the rest on save. Toggle Mirror X / Mirror Z in the X menu."));

        LOGGER.info("[DungeonTrain] Editor enter: {} -> tunnel_{} default plot at {} ({} variants, {})",
            player.getName().getString(), variant.name().toLowerCase(java.util.Locale.ROOT), origin,
            TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant)).size(),
            onTop ? "top" : "inside");
    }

    /** Erase + restamp every registered variant for {@code variant}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant) {
        for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
            stampPlot(overworld, variant, name);
        }
    }

    /** Erase + restamp the single plot for {@code (variant, name)}. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        BlockPos origin = plotOrigin(variant, name);
        TunnelPlacer.eraseAt(overworld, origin);
        EditorPlotEntityClearer.discardNonPlayersIn(
            overworld, origin, new Vec3i(TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH));
        if (variant == TunnelVariant.SECTION) {
            TunnelPlacer.placeSectionNamed(overworld, origin, name);
        } else {
            // Always render the unmirrored (entrance) orientation in the editor;
            // the exit variant at world paint time is the same template with
            // StructurePlaceSettings.setMirror(Mirror.FRONT_BACK).
            TunnelPlacer.placePortalNamed(overworld, origin, false, name);
        }
        // STRUCTURE_VOID corner-wedge overlay so saved templates strip those
        // positions and in-world stamps leave the surrounding rock alone.
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.fillCornersWithVoid(overworld, origin.getX(), tg);
        setOutline(overworld, origin, OUTLINE_BLOCK);
        captureTunnelSnapshot(overworld, origin, variant, name);
    }

    /** Erase every variant plot for {@code variant}. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant) {
        for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
            BlockPos origin = plotOrigin(variant, name);
            TunnelPlacer.eraseAt(overworld, origin);
            setOutline(overworld, origin, Blocks.AIR.defaultBlockState());
            EditorPlotSnapshots.clear(tunnelSnapshotKey(variant, name));
        }
    }

    /** Erase a single named variant plot for {@code variant} — interior + outline cleared to air. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        BlockPos origin = plotOrigin(variant, name);
        TunnelPlacer.eraseAt(overworld, origin);
        setOutline(overworld, origin, Blocks.AIR.defaultBlockState());
        EditorPlotSnapshots.clear(tunnelSnapshotKey(variant, name));
    }

    /** Snapshot the freshly-stamped tunnel region for {@link EditorDirtyCheck}'s baseline. */
    private static void captureTunnelSnapshot(ServerLevel overworld, BlockPos origin, TunnelVariant variant, String name) {
        EditorPlotSnapshots.capture(tunnelSnapshotKey(variant, name), overworld, origin,
            TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);
    }

    /** Snapshot key shared with {@link EditorDirtyCheck} for tunnel rows. */
    public static String tunnelSnapshotKey(TunnelVariant variant, String name) {
        return EditorPlotSnapshots.key("tracks",
            "tunnel_" + variant.name().toLowerCase(java.util.Locale.ROOT) + ":" + name);
    }

    /**
     * Save the captured 10×14×13 region for the {@code (variant, name)}
     * the player is currently standing in. When {@link EditorDevMode} is on,
     * also writes the captured template (and its variant-blocks sidecar) into
     * the source tree at {@code src/main/resources/data/dungeontrain/tunnels/...}
     * so authored tunnels ship with the next build — parity with
     * {@link TrackEditor#save} and {@link PillarEditor#save}.
     */
    public static SaveResult save(ServerPlayer player, TunnelVariant variant) throws IOException {
        // Player-position-resolved overload — see save(player, TunnelTemplateId)
        // for the explicit-name version used by the template label menu and
        // save-all iteration.
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");

        TunnelPlot loc = plotContainingNamed(player.blockPosition());
        if (loc == null || loc.variant() != variant) {
            throw new IOException("Player is not inside any tunnel_"
                + variant.name().toLowerCase(java.util.Locale.ROOT) + " plot.");
        }
        return save(player, new games.brennan.dungeontrain.template.TunnelTemplateId(variant, loc.name()));
    }

    /**
     * Save the captured template for the explicitly-named tunnel variant.
     * Does NOT consult player position — the caller has already resolved
     * the {@code (variant, name)} pair.
     */
    public static SaveResult save(ServerPlayer player, games.brennan.dungeontrain.template.TunnelTemplateId id) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();

        TunnelVariant variant = id.variant();
        String name = id.name();
        BlockPos origin = plotOrigin(id);
        TrackKind kind = TunnelTemplateStore.tunnelKind(variant);
        Vec3i size = new Vec3i(TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);

        // Author edits only the low master quarter; rebuild the rest in-world by
        // reflecting across the axes enabled in the template's mirror config,
        // before capture — so the stored template (and every generated tunnel)
        // is unchanged. The chest stays single (see mirrorAuthoredHalf).
        mirrorAuthoredHalf(overworld, origin, TrackVariantBlocks.loadFor(kind, name, size));

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);

        TrackVariantStore.save(kind, name, template);

        // Refresh the dirty-check baseline.
        captureTunnelSnapshot(overworld, origin, variant, name);

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_tunnel");
        LOGGER.info("[DungeonTrain] Editor save: {} -> tunnel_{}/{} template",
            player.getName().getString(),
            variant.name().toLowerCase(java.util.Locale.ROOT), name);

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TunnelTemplateStore.saveToSource(variant, name, template);
            try {
                Vec3i footprint = kind.dims(CarriageDims.clamp(
                    CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT));
                TrackVariantBlocks.loadFor(kind, name, footprint).saveToSource(kind, name);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Tunnel editor save: variant sidecar source write failed for {}: {}",
                    variant.name().toLowerCase(java.util.Locale.ROOT), e.toString());
            }
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Tunnel editor save: source write failed for {}: {}",
                variant.name().toLowerCase(java.util.Locale.ROOT), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Restore player to pre-enter position/dimension/game mode. Returns false
     * if no session — caller should then try {@link CarriageEditor#exit}.
     */
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
        return true;
    }

    /** Draw the bedrock cage along the 12 edges of the 10×14×13 plot. */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + TunnelPlacer.LENGTH;
        int y1 = origin.getY() + TunnelPlacer.HEIGHT;
        int z1 = origin.getZ() + TunnelPlacer.WIDTH;

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
    }

    // ─── Half / quarter-model mirroring ────────────────────────────────
    //
    // Tunnels are symmetric across the Z (width) and X (length) axes. The
    // author edits only the low master quarter (x ≤ MIRROR_LAST_MASTER_X,
    // z ≤ MIRROR_CENTER_Z) and the rest is rebuilt on save, per the
    // template's mirror config. Keeps the generated tunnel identical while
    // cutting authoring work.

    /** Last local X column in the master (low) half; far X is {@code >} this. 10-wide → 4. */
    static final int MIRROR_LAST_MASTER_X = (TunnelPlacer.LENGTH - 1) / 2;

    /** Mirror plane column on Z (centre); master Z is {@code ≤} this, far Z {@code >}. 13-wide → 6. */
    static final int MIRROR_CENTER_Z = (TunnelPlacer.WIDTH - 1) / 2;

    /** Reflect a local X across the X mirror plane (between x4/x5). Its own inverse. */
    static int mirrorTargetX(int x) {
        return (TunnelPlacer.LENGTH - 1) - x; // 9 - x
    }

    /** Reflect a local Z across {@link #MIRROR_CENTER_Z}. Its own inverse. */
    static int mirrorTargetZ(int z) {
        return (TunnelPlacer.WIDTH - 1) - z; // 12 - z
    }

    /**
     * Source local X for target column {@code dx}: a far-half column reflects
     * back into the master half when {@code mirrorX}; a master column (or
     * X-mirror off) maps to itself. Pure — unit-tested in {@code TunnelMirrorMapTest}.
     */
    static int sourceX(int dx, boolean mirrorX) {
        return (mirrorX && dx > MIRROR_LAST_MASTER_X) ? mirrorTargetX(dx) : dx;
    }

    /** Source local Z for target column {@code dz} — the Z analogue of {@link #sourceX}. */
    static int sourceZ(int dz, boolean mirrorZ) {
        return (mirrorZ && dz > MIRROR_CENTER_Z) ? mirrorTargetZ(dz) : dz;
    }

    /**
     * Rebuild the non-master region of the tunnel plot from the authored low
     * master quarter ({@code x ≤ }{@link #MIRROR_LAST_MASTER_X}{@code , z ≤ }
     * {@link #MIRROR_CENTER_Z}) by reflecting across the axes enabled in the
     * template's {@code sidecar} mirror config. Runs in-world immediately
     * before {@link #save} captures the region, so the stored template — and
     * therefore every generated tunnel — is unchanged; only the authoring
     * workflow differs.
     *
     * <p>Each non-master cell {@code (dx,dy,dz)} is copied from its master
     * source {@code (sourceX, dy, sourceZ)} with the block state reflected by
     * the axes that moved — {@link Mirror#FRONT_BACK} for X (EAST↔WEST),
     * {@link Mirror#LEFT_RIGHT} for Z (NORTH↔SOUTH); perpendicular axes compose.
     * Sources always lie in the master quarter, which is never written, so
     * there is no read-after-write hazard.</p>
     *
     * <p>Sidecar marker cells (the section chest at local {@code [7,1,1]}) are
     * the intentional asymmetry: a marker on the source side becomes AIR at the
     * target (no duplicate), and a marker already on a target cell is preserved.
     * The chest is re-placed once by the sidecar at generation. Structural
     * blocks carry no block-entity data, so a plain state copy suffices
     * ({@code fillFromWorld(takeEntities=false)} ignores entities such as a
     * decorative minecart).</p>
     */
    private static void mirrorAuthoredHalf(ServerLevel level, BlockPos origin, TrackVariantBlocks sidecar) {
        boolean mirrorX = sidecar.mirrorX();
        boolean mirrorZ = sidecar.mirrorZ();
        if (!mirrorX && !mirrorZ) return;
        Set<BlockPos> markers = new HashSet<>();
        for (var entry : sidecar.entries()) markers.add(entry.localPos().immutable());
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < TunnelPlacer.LENGTH; dx++) {
            int sx = sourceX(dx, mirrorX);
            for (int dz = 0; dz < TunnelPlacer.WIDTH; dz++) {
                int sz = sourceZ(dz, mirrorZ);
                if (sx == dx && sz == dz) continue; // master cell — author's work, untouched
                for (int dy = 0; dy < TunnelPlacer.HEIGHT; dy++) {
                    // Preserve a marker already sitting on this (far-side) target.
                    if (markers.contains(new BlockPos(dx, dy, dz))) continue;
                    BlockPos tgt = origin.offset(dx, dy, dz);
                    if (markers.contains(new BlockPos(sx, dy, sz))) {
                        // Don't duplicate the chest — leave its airspace background (AIR).
                        SilentBlockOps.setBlockSilent(level, tgt, air);
                        continue;
                    }
                    BlockState s = level.getBlockState(origin.offset(sx, dy, sz));
                    if (sx != dx) s = s.mirror(Mirror.FRONT_BACK);
                    if (sz != dz) s = s.mirror(Mirror.LEFT_RIGHT);
                    SilentBlockOps.setBlockSilent(level, tgt, s);
                }
            }
        }
    }

    /** Reference to silence "unused" warnings for the world-data import. */
    @SuppressWarnings("unused")
    private static final Class<?> WORLD_DATA = DungeonTrainWorldData.class;

    /** Reference to silence "unused" warning for the list import. */
    @SuppressWarnings("unused")
    private static final Class<?> LIST_REF = List.class;
}
