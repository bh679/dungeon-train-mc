package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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
 *
 * <p>Ghast density <b>escalates per lap</b>. Both the per-attempt odds ({@link #ghastDenomFor}) and the
 * ghast-specific ceiling ({@link #ghastCapFor}) key off {@link NetherBand#netherPassIndex} — the 0-based
 * world-gen cycle repeat, which doubles as the Nether pass index because the Nether band is the first
 * special band of every period. The first Nether reads as an occasional threat; each full lap back
 * around makes it denser, up to a floor of 1-in-2 odds and the top of the {@link #GHAST_NEARBY_CAPS}
 * table. The pass index is positional (derived from world-X), so this needs no saved state and is
 * reproducible across reloads. Ghasts keep their own cap on top of the shared {@link #NEARBY_CAP} so
 * they can't crowd the ground rosters out entirely.</p>
 *
 * <p>The ground roster + ghast frequency follow the <b>biome</b> at the spawn column (the core now
 * cycles through all five Nether biomes), so each biome reads right: skeletons in soul sand valleys,
 * endermen in warped forests, magma cubes in basalt deltas, piglins + hoglins in crimson forests.
 * Piglins and hoglins normally zombify (piglin) / become zoglins (hoglin) in the overworld, but
 * {@link NetherBandZombificationGuard} grants them Nether-immunity throughout the band core
 * ({@link NetherBand#isInNetherBiome}, the same {@code netherRamp ≥ 0.5} zone this spawner uses), so
 * they survive intact. Every other roster mob (zombified piglins, magma cubes, skeletons, endermen,
 * ghasts) already behaves correctly outside the Nether.</p>
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
    /** Ghast odds (1-in-N) on the FIRST Nether pass, in the biomes vanilla fills with ghasts. */
    private static final int GHAST_DENOM_DENSE_BIOMES = 4;
    /** Ghast odds (1-in-N) on the first pass everywhere else in the core. */
    private static final int GHAST_DENOM_OTHER_BIOMES = 8;
    /** Densest the odds ever get, however many laps deep — 1-in-2 is already a coin flip. */
    private static final int GHAST_DENOM_FLOOR = 2;
    /**
     * Max nether-band ghasts near a player, indexed by Nether pass; laps past the end hold the last
     * value. Escalates faster than linearly so late laps feel qualitatively different, not just denser.
     *
     * <p><b>Note the interaction with {@link #NEARBY_CAP}</b> (10, checked first and deliberately left
     * alone): the spawner stops entirely once 10 band mobs are near a player, so entries above 10 never
     * bind. In practice pass 2 (8) already leaves only ~2 slots for ground mobs, and passes 3+ mean
     * "every one of the 10 slots may be a ghast" — so laps 3 and 4 behave identically. The 15s are the
     * intended ceiling should {@code NEARBY_CAP} ever rise, not a live difference today.</p>
     */
    private static final int[] GHAST_NEARBY_CAPS = {3, 5, 8, 15, 15};
    /** Spawn attempts per player per tick. */
    private static final int TRIES = 4;
    /** Vertical search window around the player for a valid floor. */
    private static final int FLOOR_SEARCH_UP = 5;
    private static final int FLOOR_SEARCH_DOWN = 10;
    /** How high above the player to scan for an open-air pocket a ghast's 4×4×4 body fits in. */
    private static final int GHAST_POCKET_CEILING = 18;

    /** Tag for accounting only (NOT the carriage-contents prefix, so kill-ahead/confinement ignore it). */
    private static final String NETHER_MOB_TAG = "dungeontrain_nether_band_mob";

    // Per-biome ground rosters — only mobs that DON'T convert outside the Nether (no piglins/hoglins).
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] GROUND_MOBS = new EntityType[] {
            EntityType.ZOMBIFIED_PIGLIN, EntityType.MAGMA_CUBE        // nether_wastes (default)
    };
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] SOUL_SAND_MOBS = new EntityType[] {
            EntityType.SKELETON                                       // soul sand valley
    };
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] WARPED_MOBS = new EntityType[] {
            EntityType.ENDERMAN                                       // warped forest
    };
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] CRIMSON_MOBS = new EntityType[] {
            // crimson forest's real roster — kept un-zombified in-core by NetherBandZombificationGuard
            EntityType.PIGLIN, EntityType.HOGLIN, EntityType.ZOMBIFIED_PIGLIN
    };
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] BASALT_MOBS = new EntityType[] {
            EntityType.MAGMA_CUBE                                     // basalt deltas
    };

    private NetherMobSpawner() {}

    /** Ground roster for the Nether biome at the spawn column (non-converting mobs only). */
    private static EntityType<? extends Mob>[] groundMobsFor(Holder<Biome> biome) {
        if (biome.is(Biomes.SOUL_SAND_VALLEY)) return SOUL_SAND_MOBS;
        if (biome.is(Biomes.WARPED_FOREST)) return WARPED_MOBS;
        if (biome.is(Biomes.CRIMSON_FOREST)) return CRIMSON_MOBS;
        if (biome.is(Biomes.BASALT_DELTAS)) return BASALT_MOBS;
        return GROUND_MOBS; // nether_wastes / anything else
    }

    /**
     * Ghast spawn odds (1-in-N) at the biome and Nether pass of a spawn attempt — denser in the
     * biomes vanilla fills with ghasts, and one step denser per completed lap.
     */
    private static int ghastChanceDenom(Holder<Biome> biome, long pass) {
        boolean dense = biome.is(Biomes.SOUL_SAND_VALLEY) || biome.is(Biomes.BASALT_DELTAS);
        return ghastDenomFor(dense, pass);
    }

    /**
     * Ghast odds (1-in-N) at a given Nether pass — one step denser per completed lap, floored at
     * {@link #GHAST_DENOM_FLOOR}. Package-private and param-in (no biome holder, no config read) so
     * the ramp unit-tests without a NeoForge bootstrap, mirroring {@code DifficultyProgression.rawTier}.
     *
     * <p>{@code pass} is clamped at 0, which absorbs the {@code -1} "band off / before the cycle"
     * sentinel {@link NetherBand#netherPassIndex} returns — a disabled band reads as the first pass
     * rather than one step sparser than it.</p>
     */
    static int ghastDenomFor(boolean denseBiome, long pass) {
        int base = denseBiome ? GHAST_DENOM_DENSE_BIOMES : GHAST_DENOM_OTHER_BIOMES;
        return (int) Math.max(GHAST_DENOM_FLOOR, base - Math.max(0L, pass));
    }

    /**
     * Max ghasts near a player at a given Nether pass — a {@link #GHAST_NEARBY_CAPS} lookup, holding
     * the last entry for every lap beyond the table. This escalates alongside {@link #ghastDenomFor}
     * on purpose: with a fixed cap the odds barely matter, since the cap fills within seconds and
     * becomes the real ceiling, so raising the odds alone would change respawn latency rather than
     * felt difficulty.
     */
    static int ghastCapFor(long pass) {
        // Clamp into the table BEFORE indexing, so a huge or negative pass can't run off either end.
        long lap = Math.min(Math.max(0L, pass), GHAST_NEARBY_CAPS.length - 1L);
        return GHAST_NEARBY_CAPS[(int) lap];
    }

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

            // Which lap this is. Read at the PLAYER's column so every attempt this tick shares one
            // value; spawn columns are within SPAWN_RADIUS, so they only differ right at a band edge.
            long pass = NetherBand.netherPassIndex(level, px);

            // Ghasts get their own ceiling on top of NEARBY_CAP — reuse the list already fetched above.
            long ghastsNearby = nearby.stream().filter(m -> m instanceof Ghast).count();
            boolean ghastsAllowed = ghastsNearby < ghastCapFor(pass);

            for (int i = 0; i < TRIES; i++) {
                trySpawnNear(level, player, rng, ghastsAllowed, pass);
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

    private static void trySpawnNear(ServerLevel level, ServerPlayer player, RandomSource rng,
                                     boolean ghastsAllowed, long pass) {
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

        // Biome at the spawn column drives the roster + ghast frequency (the core cycles all 5 Nether biomes).
        Holder<Biome> biome = level.getBiome(probe);

        // Capped-out ghast rolls fall through to the ground path rather than wasting the attempt,
        // so the band's overall density is unchanged — only the ghast share drops.
        boolean ghast = ghastsAllowed && rng.nextInt(ghastChanceDenom(biome, pass)) == 0;
        if (ghast) {
            // A ghast is 4×4×4 — scan up from the (randomised) start height for the first pocket
            // its whole body actually fits in, so it never materialises inside terrain and suffocates.
            int startY = player.getBlockY() + 4 + rng.nextInt(8);
            for (int y = startY; y <= player.getBlockY() + GHAST_POCKET_CEILING; y++) {
                BlockPos air = new BlockPos(wx, y, wz);
                if (hasRoomFor(level, EntityType.GHAST, air)) {
                    spawn(level, EntityType.GHAST, air, rng);
                    return;
                }
            }
            return; // no open pocket this attempt — TRIES gives more chances
        }

        // Ground mob: find a solid floor with 2 air above, near the player's Y.
        EntityType<? extends Mob>[] roster = groundMobsFor(biome);
        for (int y = player.getBlockY() + FLOOR_SEARCH_UP; y >= player.getBlockY() - FLOOR_SEARCH_DOWN; y--) {
            BlockPos feet = new BlockPos(wx, y, wz);
            if (!level.getBlockState(feet.below()).blocksMotion()) continue;
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
            EntityType<? extends Mob> type = roster[rng.nextInt(roster.length)];
            spawn(level, type, feet, rng);
            return;
        }
    }

    /**
     * True when {@code type}'s full bounding box, spawned centred on {@code pos}, clears all block
     * collision. Uses the same position {@link #spawn} hands to {@link Mob#moveTo} (centre of the
     * column, feet at {@code pos.getY()}), so the test matches where the mob actually lands.
     */
    private static boolean hasRoomFor(ServerLevel level, EntityType<?> type, BlockPos pos) {
        AABB box = type.getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.noCollision(box);
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
