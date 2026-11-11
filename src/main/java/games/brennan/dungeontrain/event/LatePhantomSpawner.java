package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.spawn.LatePhantomRate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageSpace;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandSky;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Phantoms for the deep part of a run: once a player is past the first Far Lands exit they come without
 * insomnia, at a rate that climbs with the distance past it ({@link LatePhantomRate}). Vanilla's own
 * insomnia phantoms are untouched and still arrive on top.
 *
 * <p>Otherwise vanilla's {@code PhantomSpawner} rules: a survival player at or above sea level who can see
 * the sky, the group appearing 20–34 blocks overhead. Night (vanilla's {@code skyDarken >= 5}) runs at the
 * full chance; by day the chance is a quarter and the group wears Fire Resistance so it doesn't burn.</p>
 *
 * <p><b>Only under the overworld sky.</b> Vanilla never spawns phantoms in the End or the Nether, so a
 * player under the End/void sky box or the Nether sky box ({@link BandSky}, the same intensities the client
 * sky renders) gets none. <b>Never in a dimensional carriage</b>: the player and the spawn point are both
 * tested against the twin space and every portal structure.</p>
 *
 * <p>Built with {@link MobSpawnType#EVENT} and {@code finalizeSpawn} called here, as
 * {@link NetherMobSpawner} does, so the {@code FinalizeSpawnEvent} guards never see these mobs; hence the
 * portal and carriage tests are made here.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LatePhantomSpawner {

    /** Ticks between checks for each player (30 s). */
    private static final int CHECK_PERIOD_TICKS = 600;
    /** Max late phantoms near a player before the spawner backs off. */
    private static final int NEARBY_CAP = 6;
    /** Radius the cap counts within. */
    private static final int NEARBY_RADIUS = 48;
    /** Vanilla's spawn height above the player: 20 plus 0–14. */
    private static final int SPAWN_HEIGHT_MIN = 20;
    private static final int SPAWN_HEIGHT_SPREAD = 15;
    /** Vanilla's horizontal jitter, ±10. */
    private static final int SPAWN_JITTER = 10;
    /** Vanilla's sky-darkness threshold for night (or a thunderstorm). */
    private static final int NIGHT_SKY_DARKEN = 5;
    /** Tags every phantom this class spawns, for the cap. */
    public static final String LATE_PHANTOM_TAG = "dungeontrain_late_phantom";

    /** Cycle the cached exit was read from; a config reload builds a new one, which drops the cache. */
    private static WorldGenCycle cachedCycle;
    private static long cachedExitX = Long.MAX_VALUE;

    private LatePhantomSpawner() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % CHECK_PERIOD_TICKS != 0) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return;

        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long exitX = farLandsExitX(cycle);
        if (exitX == Long.MAX_VALUE) return;

        boolean night = level.getSkyDarken() >= NIGHT_SKY_DARKEN;
        RandomSource rng = level.getRandom();
        CarriageDims dims = data.dims();
        for (ServerPlayer player : level.players()) {
            long steps = LatePhantomRate.steps(player.getX(), exitX);
            if (steps < 0L || !eligible(level, player, cycle, dims)) continue;
            if (rng.nextDouble() >= LatePhantomRate.chance(steps, night)) continue;
            AABB around = player.getBoundingBox().inflate(NEARBY_RADIUS);
            int nearby = level.getEntitiesOfClass(Phantom.class, around,
                    p -> p.getTags().contains(LATE_PHANTOM_TAG)).size();
            int group = Math.min(LatePhantomRate.groupSize(steps), NEARBY_CAP - nearby);
            if (group > 0) spawnGroup(level, player, rng, dims, group, night);
        }
    }

    /** Vanilla's player tests minus insomnia, plus the sky box and the dimensional carriages. */
    private static boolean eligible(ServerLevel level, ServerPlayer player, WorldGenCycle cycle, CarriageDims dims) {
        if (player.isSpectator() || player.isCreative() || !player.isAlive()) return false;
        BlockPos pos = player.blockPosition();
        if (pos.getY() < level.getSeaLevel() || !level.canSeeSky(pos)) return false;
        int x = pos.getX();
        if (!LatePhantomRate.overworldSky(BandSky.endSkyIntensityAt(cycle, x), BandSky.netherSkyIntensityAt(cycle, x))) {
            return false;
        }
        if (PortalTwinSpace.isInside(level, x, player.getY())) return false;
        return !PortalCarriageEvents.isInsidePortalStructure(dims, player.getX(), player.getY(), player.getZ());
    }

    private static void spawnGroup(ServerLevel level, ServerPlayer player, RandomSource rng, CarriageDims dims,
                                   int group, boolean night) {
        BlockPos pos = player.blockPosition()
                .above(SPAWN_HEIGHT_MIN + rng.nextInt(SPAWN_HEIGHT_SPREAD))
                .east(-SPAWN_JITTER + rng.nextInt(2 * SPAWN_JITTER + 1))
                .south(-SPAWN_JITTER + rng.nextInt(2 * SPAWN_JITTER + 1));
        if (!level.isLoaded(pos)) return;
        if (PortalTwinSpace.isInside(level, pos.getX(), pos.getY())) return;
        if (PortalCarriageEvents.isInsidePortalStructure(dims, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)) return;
        AABB box = EntityType.PHANTOM.getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (!level.noCollision(box) || !level.getFluidState(pos).isEmpty()) return;
        if (CarriageSpace.overlapsAnyCarriage(level, box)) return;
        for (int i = 0; i < group; i++) {
            Phantom phantom = EntityType.PHANTOM.create(level);
            if (phantom == null) return;
            phantom.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rng.nextFloat() * 360.0f, 0.0f);
            try {
                phantom.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
            } catch (Throwable ignored) {
                // finalizeSpawn is best-effort; the phantom still functions with defaults.
            }
            // A daytime phantom would burn within seconds of arriving.
            if (!night) {
                phantom.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION,
                        0, false, false));
            }
            phantom.addTag(LATE_PHANTOM_TAG);
            level.addFreshEntity(phantom);
        }
    }

    /**
     * World-X of the first lap's Far Lands exit, or {@link Long#MAX_VALUE} when the order has no Far Lands.
     * Later laps' Far Lands sit further out still, so everything beyond this is "past the Far Lands".
     */
    static long farLandsExitX(WorldGenCycle cycle) {
        if (cycle != cachedCycle) {
            long exit = Long.MAX_VALUE;
            if (cycle.hasLayout()) {
                for (TransitionLandmarks.Landmark lm : TransitionLandmarks.forRun(cycle, 0)) {
                    if (lm.name().equals("far_lands_exit")) {
                        exit = lm.worldX();
                        break;
                    }
                }
            }
            cachedExitX = exit;
            cachedCycle = cycle;
        }
        return cachedExitX;
    }
}
