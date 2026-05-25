package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.registry.effect.WarmthOfTheFireEffect;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Slow proximity heal from lit soul campfires. Once per {@link #SCAN_PERIOD_TICKS},
 * every server player whose health is below max gets the
 * {@link WarmthOfTheFireEffect "Warmth Of The Fire"} effect applied (or refreshed)
 * if a {@code minecraft:soul_campfire} with {@link CampfireBlock#LIT}=true sits
 * within a {@link #RADIUS_H} horizontal / {@link #RADIUS_V} vertical box around
 * their feet — either in the visible world OR on a Sable carriage.
 *
 * <p>For ship-mounted campfires we iterate the carriage's plot block entities
 * (campfires ARE block entities). Querying the plot via {@code getBlockState}
 * on the embedded accessor is unreliable for our use — see
 * {@link games.brennan.dungeontrain.command.DebugCommand} line ~345 for the
 * precedent that "worldAABB() returns the rendered post-transform position,
 * not the storage coords — iterate the plot's loaded chunks instead".</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SoulCampfireHealEvents {

    /** Under the jitter namespace so DungeonTrain.commonSetup's DEBUG level applies. */
    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter.soulcampfire");

    /** Tick interval between scans for nearby lit soul campfires. */
    private static final int SCAN_PERIOD_TICKS = 40;
    /** Horizontal scan radius around the player's feet. */
    private static final int RADIUS_H = 2;
    /** Vertical scan radius — same as horizontal for a tight 5×5×5 cube. */
    private static final int RADIUS_V = 2;

    private static int tickCounter = 0;

    private SoulCampfireHealEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (Math.floorMod(tickCounter++, SCAN_PERIOD_TICKS) != 0) return;

        for (ServerPlayer player : level.players()) {
            if (player.isDeadOrDying() || player.isSpectator() || player.isCreative()) continue;
            if (player.getHealth() >= player.getMaxHealth()) continue;

            if (scanWorldForLitSoulCampfire(level, player.blockPosition())
                || scanShipsForLitSoulCampfire(level, player)) {
                applyWarmth(player);
            }
        }
    }

    private static void applyWarmth(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(
            ModMobEffects.WARMTH_OF_THE_FIRE,
            WarmthOfTheFireEffect.HEAL_PERIOD_TICKS,
            0,
            true,   // ambient — quieter HUD swirls
            true    // showIcon
        ));
    }

    /** Visible-world scan: cubic box around the player's feet. */
    private static boolean scanWorldForLitSoulCampfire(ServerLevel level, BlockPos centre) {
        BlockPos min = centre.offset(-RADIUS_H, -RADIUS_V, -RADIUS_H);
        BlockPos max = centre.offset(RADIUS_H, RADIUS_V, RADIUS_H);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.SOUL_CAMPFIRE) && state.getValue(CampfireBlock.LIT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check every loaded Sable ship in the level. For each ship whose visible
     * AABB encloses the player (expanded by the scan radius), iterate the
     * ship's plot block entities — soul campfire is a {@code CampfireBlockEntity},
     * so it shows up here. Chebyshev-distance filter against the player's
     * ship-local position handles the "in proximity" check.
     */
    private static boolean scanShipsForLitSoulCampfire(ServerLevel level, ServerPlayer player) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (px < bb.minX() - RADIUS_H || px > bb.maxX() + RADIUS_H) continue;
            if (py < bb.minY() - RADIUS_V || py > bb.maxY() + RADIUS_V) continue;
            if (pz < bb.minZ() - RADIUS_H || pz > bb.maxZ() + RADIUS_H) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Vector3d local = new Vector3d(px, py, pz);
            ship.worldToShip(local);
            BlockPos shipCentre = BlockPos.containing(local.x, local.y, local.z);

            ServerSubLevel subLevel = sableShip.subLevel();
            LevelPlot plot = subLevel.getPlot();
            int campfiresSeen = 0;
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockEntity be = entry.getValue();
                    BlockState state = be.getBlockState();
                    if (!state.is(Blocks.SOUL_CAMPFIRE)) continue;
                    if (!state.getValue(CampfireBlock.LIT)) continue;
                    campfiresSeen++;
                    BlockPos pos = entry.getKey();
                    if (Math.abs(pos.getX() - shipCentre.getX()) <= RADIUS_H
                        && Math.abs(pos.getY() - shipCentre.getY()) <= RADIUS_V
                        && Math.abs(pos.getZ() - shipCentre.getZ()) <= RADIUS_H) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                "[SoulCampfire] HIT on ship {} shipLocal=({},{},{}) campfireAt=({},{},{})",
                                ship.id(),
                                shipCentre.getX(), shipCentre.getY(), shipCentre.getZ(),
                                pos.getX(), pos.getY(), pos.getZ());
                        }
                        return true;
                    }
                }
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "[SoulCampfire] miss on ship {} shipLocal=({},{},{}) litCampfiresInPlot={}",
                    ship.id(),
                    shipCentre.getX(), shipCentre.getY(), shipCentre.getZ(),
                    campfiresSeen);
            }
        }
        return false;
    }
}
