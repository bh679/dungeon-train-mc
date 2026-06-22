package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Populates the Nether transition band's core with Nether mobs and suppresses
 * overworld ambient spawns there. Nether mobs are NOT in any overworld biome's spawn
 * list, so the natural spawner never proposes them — this <b>actively</b> spawns
 * zombified piglins, magma cubes and ghasts around players inside the nether core,
 * using {@link MobSpawnType#EVENT} (so the ambient-cancel rule never fights its own
 * spawns), and cancels NATURAL/CHUNK_GENERATION overworld spawns across the band so the
 * mountains/nether don't fill with zombies and creepers. The End band always wins —
 * columns it owns ({@link DisintegrationBand#middleRampAt} {@code > 0}) are skipped.
 *
 * <p>Regular piglins are deliberately omitted (they zombify in the overworld dimension);
 * zombified piglins, magma cubes and ghasts all behave correctly outside the Nether.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherMobSpawner {

    /** How often (ticks) the spawner runs. */
    private static final int SPAWN_PERIOD_TICKS = 40;
    /** netherRamp at/above which mobs spawn — the netherrack + real-Nether stretch. */
    private static final double SPAWN_INTENSITY_THRESHOLD = 0.5;
    /** Horizontal radius around a player that mobs spawn within. */
    private static final int SPAWN_RADIUS = 40;
    /** Mobs never spawn closer than this to the player. */
    private static final int MIN_SPAWN_DIST = 16;
    /** Max nether-band mobs near a player before the spawner backs off. */
    private static final int NEARBY_CAP = 10;
    /** Spawn attempts per player per tick. */
    private static final int TRIES = 4;
    /** Vertical search window around the player for a valid floor. */
    private static final int FLOOR_SEARCH_UP = 5;
    private static final int FLOOR_SEARCH_DOWN = 10;

    /** Tag for accounting only (NOT the carriage-contents prefix, so kill-ahead/confinement ignore it). */
    private static final String NETHER_MOB_TAG = "dungeontrain_nether_band_mob";

    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] GROUND_MOBS = new EntityType[] {
            EntityType.ZOMBIFIED_PIGLIN, EntityType.MAGMA_CUBE
    };

    private NetherMobSpawner() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % SPAWN_PERIOD_TICKS != 0) return;
        if (NetherBand.startX(level) == NetherBand.OFF) return;

        RandomSource rng = level.getRandom();
        for (ServerPlayer player : level.players()) {
            int px = (int) Math.floor(player.getX());
            if (NetherBand.netherRampAt(level, px) < SPAWN_INTENSITY_THRESHOLD) continue;
            if (DisintegrationBand.middleRampAt(level, px) > 0.0) continue;

            AABB around = player.getBoundingBox().inflate(SPAWN_RADIUS);
            List<Mob> nearby = level.getEntitiesOfClass(Mob.class, around,
                    m -> m.getTags().contains(NETHER_MOB_TAG));
            if (nearby.size() >= NEARBY_CAP) continue;

            for (int i = 0; i < TRIES; i++) {
                trySpawnNear(level, player, rng);
            }
        }
    }

    /** Suppress overworld ambient spawns anywhere in the nether band (not owned by the End band). */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;
        ServerLevel level = event.getLevel().getLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        int x = (int) Math.floor(event.getX());
        if (NetherBand.heightRampAt(level, x) > 0.0 && DisintegrationBand.middleRampAt(level, x) <= 0.0) {
            event.setSpawnCancelled(true);
        }
    }

    private static void trySpawnNear(ServerLevel level, ServerPlayer player, RandomSource rng) {
        int dx = rng.nextInt(2 * SPAWN_RADIUS + 1) - SPAWN_RADIUS;
        int dz = rng.nextInt(2 * SPAWN_RADIUS + 1) - SPAWN_RADIUS;
        if (dx * dx + dz * dz < MIN_SPAWN_DIST * MIN_SPAWN_DIST) return;
        int wx = (int) Math.floor(player.getX()) + dx;
        int wz = (int) Math.floor(player.getZ()) + dz;

        // Still inside the nether core at the spawn column, and not a column the End band owns.
        if (NetherBand.netherRampAt(level, wx) < SPAWN_INTENSITY_THRESHOLD) return;
        if (DisintegrationBand.middleRampAt(level, wx) > 0.0) return;

        BlockPos probe = new BlockPos(wx, player.getBlockY(), wz);
        if (!level.isLoaded(probe)) return;

        boolean ghast = rng.nextInt(6) == 0;
        if (ghast) {
            // Open-air pocket above the player level.
            BlockPos air = new BlockPos(wx, player.getBlockY() + 4 + rng.nextInt(8), wz);
            if (!level.getBlockState(air).isAir() || !level.getBlockState(air.above()).isAir()) return;
            spawn(level, EntityType.GHAST, air, rng);
            return;
        }

        // Ground mob: find a solid floor with 2 air above, near the player's Y.
        for (int y = player.getBlockY() + FLOOR_SEARCH_UP; y >= player.getBlockY() - FLOOR_SEARCH_DOWN; y--) {
            BlockPos feet = new BlockPos(wx, y, wz);
            if (!level.getBlockState(feet.below()).blocksMotion()) continue;
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
            EntityType<? extends Mob> type = GROUND_MOBS[rng.nextInt(GROUND_MOBS.length)];
            spawn(level, type, feet, rng);
            return;
        }
    }

    private static void spawn(ServerLevel level, EntityType<? extends Mob> type, BlockPos pos, RandomSource rng) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) entity.discard();
            return;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rng.nextFloat() * 360.0f, 0.0f);
        try {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        } catch (Throwable ignored) {
            // finalizeSpawn is best-effort; the mob still functions with defaults.
        }
        mob.addTag(NETHER_MOB_TAG);
        level.addFreshEntity(mob);
    }
}
