package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageFootprint;
import games.brennan.dungeontrain.train.ShipyardShifter;
import games.brennan.dungeontrain.train.TrainChainManager;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.TrainWindowManager;
import games.brennan.dungeontrain.tunnel.TunnelGenerator;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick server logic that lets Dungeon Trains carve straight through the
 * world: every tick we kill non-player entities inside each train's world AABB,
 * and every {@link #BLOCK_CLEAR_PERIOD_TICKS} ticks we destroy non-ship blocks
 * in a forward look-ahead slab. Clearing blocks ahead keeps the VS collider
 * moving over empty space — it achieves "train cannot be stopped" without
 * touching VS collision internals (no public API for that in 2.4.x).
 *
 * Blocks and entities are removed silently — no break particles, no break
 * sound, no item drops, no container spill — via
 * {@link SilentBlockOps#clearBlockSilent} and {@code entity.discard()}.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrainTickEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Diagnostic logger (DEBUG-elevated in DungeonTrain ctor). Feeds the
    // [stuck.timing] per-tick budget breakdown for the stuck-at-228
    // investigation (see plans/linear-marinating-yao.md).
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");
    // Any per-tick work over this threshold triggers a timing breakdown
    // log line at DEBUG so we can see WHICH sub-task is the culprit.
    // 5 ms = 1/10 of the 50 ms server tick budget — anything above means
    // real risk of cascading lag.
    private static final long STUCK_TIMING_THRESHOLD_MS = 5;

    // Train moves 2 m/s ≈ 0.1 block/tick (MVP velocity in TrainCommand). A 10-tick
    // period clears blocks twice per second; an 8-block look-ahead leaves 7+ blocks
    // of runway between clears. If velocity is raised later, scale LOOKAHEAD_BLOCKS
    // to at least velocity * period / 20 * safety_margin.
    private static final int BLOCK_CLEAR_PERIOD_TICKS = 10;
    private static final int LOOKAHEAD_BLOCKS = 8;
    // ~2 Hz at 20 TPS — picks up chunks that were loaded at spawn time (and so
    // never re-fire ChunkEvent.Load) plus any that moved into range since the
    // last scan. Idempotent in TrackGenerator, so re-scans are cheap (set-hit
    // per chunk). The +5 offset keeps the fill off the same tick as
    // clearBlocksAhead — otherwise both heavy operations collide every 20
    // ticks and spike the server tick past its 50 ms budget.
    private static final int TRACK_FILL_PERIOD_TICKS = 10;
    private static final int TRACK_FILL_PHASE_OFFSET = 5;
    // Tunnel fill drains at the same period as track fill but lands on a
    // separate tick (offset 2) so block-clear (offset 0), track-fill (5), and
    // tunnel-fill (2) never collide on a 10-tick cycle. 1 chunk per call keeps
    // per-tick cost flat — ~13×9×16 block writes per chunk worst case.
    private static final int TUNNEL_FILL_PERIOD_TICKS = 10;
    private static final int TUNNEL_FILL_PHASE_OFFSET = 2;

    private static int tickCounter = 0;

    private TrainTickEvents() {}

    /**
     * Clean per-player editor state on logout so a re-login doesn't see a
     * stale "already told the client about this status" entry that suppresses
     * the next HUD update.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            VariantOverlayRenderer.forget(sp);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        long t0 = System.nanoTime();

        // Rolling-window manager runs every tick regardless of whether we're also
        // carving terrain — it only adds/removes carriages when a player crosses
        // a carriage boundary, so the cost is negligible on idle ticks.
        TrainWindowManager.onLevelTick(level);
        long tAfterWindow = System.nanoTime();

        // Editor overlay — cheap when nobody is in an editor plot (short-circuits
        // per-player via CarriageEditor.plotContaining).
        VariantOverlayRenderer.onLevelTick(level);
        long tAfterOverlay = System.nanoTime();

        List<ManagedShip> trains = findTrains(level);
        long tAfterFindTrains = System.nanoTime();
        if (trains.isEmpty()) {
            tickCounter++;
            return;
        }

        for (ManagedShip ship : trains) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
                killEntitiesIn(level, ship, provider);
            }
        }
        long tAfterKill = System.nanoTime();

        // Reference-shift pass: keep the ship pivot + canonicalPos
        // advancing with the player so active carriage voxels stay within
        // VS's 128-chunk distance limit. Cheap — one per-player pivot
        // distance check per train per tick. Actual shift fires rarely
        // (~every 1000 blocks of forward travel). See ShipyardShifter.
        ShipyardShifter.shiftIfNeeded(level, trains, level.players());

        // Chain-manager pass: once the player crosses the per-train
        // trigger pIdx, spawn a successor train ahead so they have a
        // fresh shipyard allocation to walk onto before the predecessor
        // runs out. See TrainChainManager + plans/floofy-floating-dahl.md.
        TrainChainManager.maybeSpawnSuccessors(level, trains, level.players());

        if (tickCounter % BLOCK_CLEAR_PERIOD_TICKS == 0) {
            for (ManagedShip ship : trains) {
                clearBlocksAhead(level, ship);
            }
        }
        long tAfterClear = System.nanoTime();

        if (DungeonTrainConfig.getGenerateTracks()
            && Math.floorMod(tickCounter, TRACK_FILL_PERIOD_TICKS) == TRACK_FILL_PHASE_OFFSET) {
            for (ManagedShip ship : trains) {
                if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
                    TrackGenerator.fillRenderDistance(level, ship, provider);
                }
            }
        }
        long tAfterTracks = System.nanoTime();

        if (DungeonTrainConfig.getGenerateTunnels()
            && Math.floorMod(tickCounter, TUNNEL_FILL_PERIOD_TICKS) == TUNNEL_FILL_PHASE_OFFSET) {
            for (ManagedShip ship : trains) {
                if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
                    TunnelGenerator.fillRenderDistance(level, ship, provider);
                }
            }
        }
        long tAfterTunnels = System.nanoTime();

        long totalMs = (tAfterTunnels - t0) / 1_000_000;
        if (totalMs >= STUCK_TIMING_THRESHOLD_MS) {
            JITTER_LOGGER.debug(
                "[stuck.timing] tick={} total={}ms window={}ms overlay={}ms find={}ms kill={}ms clear={}ms tracks={}ms tunnels={}ms trains={}",
                level.getGameTime(), totalMs,
                (tAfterWindow - t0) / 1_000_000,
                (tAfterOverlay - tAfterWindow) / 1_000_000,
                (tAfterFindTrains - tAfterOverlay) / 1_000_000,
                (tAfterKill - tAfterFindTrains) / 1_000_000,
                (tAfterClear - tAfterKill) / 1_000_000,
                (tAfterTracks - tAfterClear) / 1_000_000,
                (tAfterTunnels - tAfterTracks) / 1_000_000,
                trains.size());
        }
        tickCounter++;
    }

    private static List<ManagedShip> findTrains(ServerLevel level) {
        List<ManagedShip> trains = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                trains.add(ship);
            }
        }
        return trains;
    }

    private static void killEntitiesIn(ServerLevel level, ManagedShip ship, TrainTransformProvider provider) {
        AABB aabb = CarriageFootprint.activeWorldAABB(ship, provider);
        if (aabb.getXsize() <= 0 || aabb.getYsize() <= 0 || aabb.getZsize() <= 0) return;
        // Once-per-~10s footprint snapshot so we can see whether our AABB is
        // actually staying bounded or silently growing like VS's worldAABB.
        // Gated to committedPIdx past the danger zone so early-ride ticks
        // stay quiet.
        if (Math.abs(provider.getCommittedPIdx()) >= 200 && tickCounter % 200 == 0) {
            JITTER_LOGGER.debug(
                "[stuck.footprint] tick={} committedPIdx={} activeIdx={} aabb=({}, {}, {}) -> ({}, {}, {}) size=({}x{}x{})",
                level.getGameTime(), provider.getCommittedPIdx(), provider.getActiveIndices().size(),
                String.format("%.2f", aabb.minX), String.format("%.2f", aabb.minY), String.format("%.2f", aabb.minZ),
                String.format("%.2f", aabb.maxX), String.format("%.2f", aabb.maxY), String.format("%.2f", aabb.maxZ),
                String.format("%.2f", aabb.getXsize()),
                String.format("%.2f", aabb.getYsize()),
                String.format("%.2f", aabb.getZsize()));
        }
        List<Entity> victims = level.getEntitiesOfClass(
            Entity.class, aabb,
            e -> !(e instanceof Player) && e.isAlive()
        );
        for (Entity e : victims) {
            e.discard();
        }
    }

    private static void clearBlocksAhead(ServerLevel level, ManagedShip ship) {
        if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) return;
        Vector3dc velocity = provider.getTargetVelocity();
        Shipyard shipyard = Shipyards.of(level);

        AABB aabb = CarriageFootprint.activeWorldAABB(ship, provider);
        if (aabb.getXsize() <= 0 || aabb.getYsize() <= 0 || aabb.getZsize() <= 0) return;
        double expX = Math.signum(velocity.x()) * LOOKAHEAD_BLOCKS;
        double expY = Math.signum(velocity.y()) * LOOKAHEAD_BLOCKS;
        double expZ = Math.signum(velocity.z()) * LOOKAHEAD_BLOCKS;

        int minX = (int) Math.floor(Math.min(aabb.minX, aabb.minX + expX));
        int minY = (int) Math.floor(Math.min(aabb.minY, aabb.minY + expY));
        int minZ = (int) Math.floor(Math.min(aabb.minZ, aabb.minZ + expZ));
        int maxX = (int) Math.floor(Math.max(aabb.maxX, aabb.maxX + expX));
        int maxY = (int) Math.floor(Math.max(aabb.maxY, aabb.maxY + expY));
        int maxZ = (int) Math.floor(Math.max(aabb.maxZ, aabb.maxZ + expZ));

        // Clamp lower Y to the carriage floor so the sweep never dips into
        // the bed or rail rows (bedY = origin.y − 2, railY = origin.y − 1).
        // VS's worldAABB can report minY one sub-pixel below origin.y due to
        // transform precision, which would otherwise floor down to railY and
        // destroy the authored rail layer 8 blocks ahead of the train.
        TrackGeometry geometry = provider.getTrackGeometry();
        if (geometry != null) {
            int carriageFloorY = geometry.bedY() + 2;
            if (minY < carriageFloorY) minY = carriageFloorY;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int destroyed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    // Never destroy ship-owned blocks — our own carriages or any other ship.
                    if (shipyard.isInShip(cursor)) continue;
                    SilentBlockOps.clearBlockSilent(level, cursor.immutable());
                    destroyed++;
                }
            }
        }
        if (destroyed > 0) {
            LOGGER.debug("[DungeonTrain] Train id={} cleared {} blocks ahead", ship.id(), destroyed);
        }
    }
}
