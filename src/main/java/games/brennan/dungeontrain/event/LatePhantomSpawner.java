package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.spawn.LatePhantomRate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageSpace;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandSky;
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
import org.slf4j.Logger;

/**
 * Phantoms for the deep part of a run: once a player reaches the second world-gen cycle (the whole first
 * cycle, Far Lands included, behind them) they come without insomnia, at a rate that climbs with the distance
 * into it ({@link LatePhantomRate}). Vanilla's own insomnia phantoms are untouched and still arrive on top.
 *
 * <p>Otherwise vanilla's {@code PhantomSpawner} rules: a survival player at or above sea level who can see
 * the sky, the group appearing 20–34 blocks overhead. Night (vanilla's {@code skyDarken >= 5}) runs at the
 * full chance; by day the chance is a quarter and the group wears Fire Resistance so it doesn't burn.</p>
 *
 * <p>Once the spawn rate tops out ({@link LatePhantomRate#STEPS_TO_MAX}) each phantom also rolls potion
 * effects off the same curve as every other mob, its level counting from that point
 * ({@link LatePhantomRate#effectLevel}, {@link DifficultyApplier#applyEffectsAtLevel}).</p>
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

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /** Cycle the cached start was read from; a config reload builds a new one, which drops the cache. */
    private static WorldGenCycle cachedCycle;
    private static long cachedStartX = Long.MAX_VALUE;

    private LatePhantomSpawner() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % CHECK_PERIOD_TICKS != 0) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return;

        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long startX = secondCycleStartX(cycle);
        if (startX == Long.MAX_VALUE) return;

        boolean night = level.getSkyDarken() >= NIGHT_SKY_DARKEN;
        RandomSource rng = level.getRandom();
        CarriageDims dims = data.dims();
        for (ServerPlayer player : level.players()) {
            long steps = LatePhantomRate.steps(player.getX(), startX);
            if (steps < 0L) continue;
            String refused = refusal(level, player, cycle, dims);
            if (refused != null) {
                LOGGER.debug("[DungeonTrain] late phantoms: {} at x={} refused ({})", player.getScoreboardName(),
                    player.getBlockX(), refused);
                continue;
            }
            double chance = LatePhantomRate.chance(steps, night);
            LOGGER.debug("[DungeonTrain] late phantoms: {} at x={} steps={} chance={} night={}",
                player.getScoreboardName(), player.getBlockX(), steps, chance, night);
            if (rng.nextDouble() >= chance) continue;
            AABB around = player.getBoundingBox().inflate(NEARBY_RADIUS);
            int nearby = level.getEntitiesOfClass(Phantom.class, around,
                    p -> p.getTags().contains(LATE_PHANTOM_TAG)).size();
            int group = Math.min(LatePhantomRate.groupSize(steps), NEARBY_CAP - nearby);
            if (group > 0) spawnGroup(level, player, rng, dims, group, night, LatePhantomRate.effectLevel(steps));
        }
    }

    /**
     * Vanilla's player tests minus insomnia, plus the sky box and the dimensional carriages: {@code null} when
     * the player qualifies, else the rule that refused them (for the debug log).
     */
    private static String refusal(ServerLevel level, ServerPlayer player, WorldGenCycle cycle, CarriageDims dims) {
        if (player.isSpectator() || player.isCreative() || !player.isAlive()) return "game mode";
        BlockPos pos = player.blockPosition();
        if (pos.getY() < level.getSeaLevel()) return "below sea level";
        if (!level.canSeeSky(pos)) return "no sky";
        int x = pos.getX();
        double end = BandSky.endSkyIntensityAt(cycle, x);
        double nether = BandSky.netherSkyIntensityAt(cycle, x);
        if (!LatePhantomRate.overworldSky(end, nether)) return "End/Nether sky end=" + end + " nether=" + nether;
        if (PortalTwinSpace.isInside(level, x, player.getY())) return "twin space";
        if (PortalCarriageEvents.isInsidePortalStructure(dims, player.getX(), player.getY(), player.getZ())) {
            return "portal structure";
        }
        return null;
    }

    private static void spawnGroup(ServerLevel level, ServerPlayer player, RandomSource rng, CarriageDims dims,
                                   int group, boolean night, int effectLevel) {
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
            // Past the top of the spawn curve the swarm stops growing, so the phantoms themselves do.
            DifficultyApplier.applyEffectsAtLevel(phantom, effectLevel, rng);
            phantom.addTag(LATE_PHANTOM_TAG);
            level.addFreshEntity(phantom);
        }
    }

    /**
     * World-X where the second cycle starts (doubling run 1, base 0), or {@link Long#MAX_VALUE} when the
     * world has no band layout. Every later cycle sits further out still, so everything beyond this counts.
     */
    static long secondCycleStartX(WorldGenCycle cycle) {
        if (cycle != cachedCycle) {
            cachedStartX = cycle.hasLayout() && cycle.period() > 0L ? cycle.worldXOfBase(1, 0L) : Long.MAX_VALUE;
            cachedCycle = cycle;
            LOGGER.info("[DungeonTrain] late phantoms start with the second cycle at x={}",
                cachedStartX == Long.MAX_VALUE ? "none" : cachedStartX);
        }
        return cachedStartX;
    }
}
