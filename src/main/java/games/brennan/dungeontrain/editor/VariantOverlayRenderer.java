package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.List;
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
            if (plotVariant == null) continue;
            if (!isEnabled(player)) continue;

            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
            if (sidecar.isEmpty()) continue;

            BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
            if (plotOrigin == null) continue;

            if (emitParticles) {
                emitOutlineParticles(level, player, plotOrigin, sidecar);
            }
            renderHoverActionBar(level, player, plotOrigin, sidecar, dims);
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

    private static void renderHoverActionBar(
        ServerLevel level, ServerPlayer player, BlockPos plotOrigin, CarriageVariantBlocks sidecar, CarriageDims dims
    ) {
        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || bhr.getType() == HitResult.Type.MISS) return;
        BlockPos worldPos = bhr.getBlockPos();
        BlockPos local = worldPos.subtract(plotOrigin);
        if (!inBounds(local, dims)) return;
        List<BlockState> states = sidecar.statesAt(local);
        if (states == null) return;

        String names = joinNames(states);
        Component msg = Component.literal("Variants (" + states.size() + "): ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal(names).withStyle(ChatFormatting.WHITE));
        player.displayClientMessage(msg, true);
    }

    private static boolean inBounds(BlockPos p, CarriageDims dims) {
        return p.getX() >= 0 && p.getX() < dims.length()
            && p.getY() >= 0 && p.getY() < dims.height()
            && p.getZ() >= 0 && p.getZ() < dims.width();
    }

    private static String joinNames(List<BlockState> states) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(states.get(i).getBlock()).toString());
        }
        return sb.toString();
    }
}
