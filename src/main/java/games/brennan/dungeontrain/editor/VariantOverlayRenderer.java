package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-driven visual overlay for the carriage editor. For every player
 * standing inside an editor plot whose overlay toggle is on (default on
 * {@link CarriageEditor#enter}), this renderer:
 *
 * <ul>
 *   <li>Emits {@link ParticleTypes#END_ROD} particles at the 12 edge
 *       midpoints of every block flagged with random variants — a cheap
 *       highlight that reads as a glowing outline from the player's PoV.</li>
 *   <li>Raycasts the player's eye every tick; if the target is a flagged
 *       block, shows an action-bar string listing the number of variants
 *       and the current block's registry name.</li>
 * </ul>
 *
 * <p>No client-side code required — particles and action-bar messages
 * travel on the vanilla networking path. Everything is per-player, so a
 * dedicated server with multiple editors only bills each player for their
 * own plot.</p>
 */
public final class VariantOverlayRenderer {

    /** Tick cadence for particle emission — every 6 ticks ≈ 3.3 Hz. */
    private static final int PARTICLE_PERIOD_TICKS = 6;

    /** Range beyond which we skip particle emission even for plots a player is "in". */
    private static final double PARTICLE_RANGE = 24.0;
    private static final double PARTICLE_RANGE_SQ = PARTICLE_RANGE * PARTICLE_RANGE;

    /** Raycast distance for the hover action bar. */
    private static final double HOVER_REACH = 8.0;

    /** Number of particles per edge of a flagged block. 2 = one near each corner for visibility. */
    private static final int PARTICLES_PER_EDGE = 2;

    /** Players who have turned the overlay OFF. Default is "on when in an editor plot". */
    private static final Set<UUID> DISABLED = new HashSet<>();

    /**
     * Per-player "last position we sent a hover packet for" — so we only
     * push a new packet when the player crosses a block boundary or stops
     * looking at a variant-flagged block. Null value means "last packet was
     * the empty-clear".
     */
    private static final Map<UUID, BlockPos> LAST_HOVER_POS = new HashMap<>();

    private VariantOverlayRenderer() {}

    /** Toggle the overlay for {@code player}. {@code on == true} resumes rendering. */
    public static void setEnabled(ServerPlayer player, boolean on) {
        if (on) DISABLED.remove(player.getUUID());
        else DISABLED.add(player.getUUID());
    }

    public static boolean isEnabled(ServerPlayer player) {
        return !DISABLED.contains(player.getUUID());
    }

    /** Drop a player's overlay preference (called on editor exit). */
    public static void forget(ServerPlayer player) {
        DISABLED.remove(player.getUUID());
        BlockPos last = LAST_HOVER_POS.remove(player.getUUID());
        // Clear the client HUD on exit so it doesn't linger in a non-editor context.
        if (last != null) {
            DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        }
    }

    /**
     * Call once per server level tick. Cheap when no players are in an
     * editor plot — the outer loop over {@code level.players()} short-circuits
     * via {@link CarriageEditor#plotContaining}.
     */
    public static void onLevelTick(ServerLevel level) {
        long tick = level.getGameTime();
        boolean emitParticles = tick % PARTICLE_PERIOD_TICKS == 0;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        for (ServerPlayer player : players) {
            CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
            if (plotVariant == null) {
                // Player left a plot — make sure a stale HUD gets cleared.
                clearHoverIfStale(player);
                continue;
            }
            if (!isEnabled(player)) {
                clearHoverIfStale(player);
                continue;
            }

            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
            if (sidecar.isEmpty()) {
                clearHoverIfStale(player);
                continue;
            }

            BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
            if (plotOrigin == null) continue;

            if (emitParticles) {
                emitOutlineParticles(level, player, plotOrigin, sidecar);
            }
            updateHoverPacket(player, plotOrigin, sidecar, dims);
        }
    }

    private static void clearHoverIfStale(ServerPlayer player) {
        if (LAST_HOVER_POS.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        }
    }

    private static void emitOutlineParticles(
        ServerLevel level, ServerPlayer player, BlockPos plotOrigin, CarriageVariantBlocks sidecar
    ) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            BlockPos world = plotOrigin.offset(e.localPos());
            double cx = world.getX() + 0.5;
            double cy = world.getY() + 0.5;
            double cz = world.getZ() + 0.5;
            double dx = cx - px;
            double dy = cy - py;
            double dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > PARTICLE_RANGE_SQ) continue;
            sendEdgeParticles(level, player, world);
        }
    }

    /**
     * 12 edges per cube, {@link #PARTICLES_PER_EDGE} particles per edge.
     * Each {@code sendParticles} call is a single packet to the player,
     * so batch count matters — 24 per block is plenty for a visible outline
     * at 3 Hz without overwhelming the pipe.
     */
    private static void sendEdgeParticles(ServerLevel level, ServerPlayer player, BlockPos world) {
        double x0 = world.getX();
        double y0 = world.getY();
        double z0 = world.getZ();
        double x1 = x0 + 1.0;
        double y1 = y0 + 1.0;
        double z1 = z0 + 1.0;

        for (int step = 0; step < PARTICLES_PER_EDGE; step++) {
            double t = (step + 0.5) / PARTICLES_PER_EDGE;

            // 4 edges along X
            particle(level, player, lerp(x0, x1, t), y0, z0);
            particle(level, player, lerp(x0, x1, t), y0, z1);
            particle(level, player, lerp(x0, x1, t), y1, z0);
            particle(level, player, lerp(x0, x1, t), y1, z1);

            // 4 edges along Y
            particle(level, player, x0, lerp(y0, y1, t), z0);
            particle(level, player, x0, lerp(y0, y1, t), z1);
            particle(level, player, x1, lerp(y0, y1, t), z0);
            particle(level, player, x1, lerp(y0, y1, t), z1);

            // 4 edges along Z
            particle(level, player, x0, y0, lerp(z0, z1, t));
            particle(level, player, x0, y1, lerp(z0, z1, t));
            particle(level, player, x1, y0, lerp(z0, z1, t));
            particle(level, player, x1, y1, lerp(z0, z1, t));
        }
    }

    private static void particle(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        level.sendParticles(player, ParticleTypes.END_ROD, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Raycast the player's eye, figure out which variant-flagged position
     * (if any) they're currently pointing at, and sync that set of candidate
     * block ids to the client so the HUD overlay can draw icons. Only sends
     * a packet when the target block changes — stationary crosshair = zero
     * network traffic.
     */
    private static void updateHoverPacket(
        ServerPlayer player, BlockPos plotOrigin, CarriageVariantBlocks sidecar, CarriageDims dims
    ) {
        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        BlockPos flaggedPos = null;
        List<BlockState> states = null;
        if (hit instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
            if (inBounds(local, dims)) {
                List<BlockState> atPos = sidecar.statesAt(local);
                if (atPos != null) {
                    flaggedPos = bhr.getBlockPos().immutable();
                    states = atPos;
                }
            }
        }

        BlockPos prev = LAST_HOVER_POS.get(player.getUUID());
        if (flaggedPos == null) {
            if (prev != null) {
                LAST_HOVER_POS.remove(player.getUUID());
                DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
            }
            return;
        }
        if (flaggedPos.equals(prev)) return;

        LAST_HOVER_POS.put(player.getUUID(), flaggedPos);
        DungeonTrainNet.sendTo(player, new VariantHoverPacket(toBlockIds(states)));
    }

    private static List<ResourceLocation> toBlockIds(List<BlockState> states) {
        List<ResourceLocation> out = new ArrayList<>(states.size());
        for (BlockState s : states) {
            out.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()));
        }
        return out;
    }

    private static boolean inBounds(BlockPos p, CarriageDims dims) {
        return p.getX() >= 0 && p.getX() < dims.length()
            && p.getY() >= 0 && p.getY() < dims.height()
            && p.getZ() >= 0 && p.getZ() < dims.width();
    }
}
