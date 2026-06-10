package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spawns a PlayerMob (bundled sibling mod "playermob", entity
 * {@code playermob:player_mob}) on a freshly-settled carriage group.
 *
 * <p>Invoked once per group from
 * {@link TrainCarriageAppender#firePendingContentsEntitySpawns}, gated by a
 * 1-in-N roll. The rate is this world's override
 * ({@link games.brennan.dungeontrain.world.DungeonTrainWorldData#getEffectivePlayerMobSpawnOneIn})
 * if set in-game, else the global default
 * ({@link games.brennan.dungeontrain.config.DungeonTrainCommonConfig}, default 10 — about one
 * in ten; set to 1 for a PlayerMob on every group). The mob is placed
 * at the interior floor-centre of a random enclosed carriage in the group, in
 * shipyard coordinates — Sable binds it to the moving carriage at
 * {@code addFreshEntity} time, exactly like editor-placed carriage contents.</p>
 *
 * <p>Two deliberate departures from
 * {@link CarriageContentsPlacer#spawnVariantMob} (which preserves an editor
 * template's exact NBT):</p>
 * <ul>
 *   <li>We call {@code Mob#finalizeSpawn} — {@code PlayerMobEntity} rolls its
 *       personality, skin, and door behaviour there. Without it every mob would
 *       share the default look and a neutral disposition.</li>
 *   <li>We stamp the {@link CarriageContentsPlacer#contentsTagFor} tag so the
 *       train's kill-ahead sweep in {@code TrainTickEvents} spares the mob —
 *       untagged entities near the train are {@code discard()}ed within a tick.
 *       The tag also opts the mob into the existing carriage-entity handlers
 *       (difficulty gear, diagnostics), consistent with other on-train mobs.</li>
 * </ul>
 */
public final class PlayerMobGroupSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entity id of the bundled PlayerMob (Interactive Player Mobs). */
    private static final ResourceLocation PLAYER_MOB_ID =
        ResourceLocation.fromNamespaceAndPath("playermob", "player_mob");

    private PlayerMobGroupSpawner() {}

    /**
     * Roll the per-group gate and, on success, spawn one PlayerMob in this group.
     *
     * @param level    server level the group lives in
     * @param provider the group's kinematic driver (anchor pIdx + diagnostics)
     * @param pending  per-carriage spawn records for this group — one non-null
     *                 entry per carriage, each carrying the carriage's
     *                 {@code shipyardOrigin} and {@code dims}
     */
    public static void maybeSpawnForGroup(ServerLevel level,
                                          TrainTransformProvider provider,
                                          PendingContentsEntitySpawn[] pending) {
        int oneIn = DungeonTrainWorldData.get(level.getServer().overworld()).getEffectivePlayerMobSpawnOneIn();
        if (oneIn <= 0) return;                       // disabled
        if (pending == null || pending.length == 0) return;

        RandomSource rng = level.getRandom();
        if (rng.nextInt(oneIn) != 0) return;          // 1-in-N gate (oneIn == 1 → always)

        PendingContentsEntitySpawn host = pickHostCarriage(pending, rng);
        if (host == null) return;                     // no enclosed carriage to stand in

        BlockPos spawnPos = interiorFloorCentre(host.shipyardOrigin(), host.dims());
        boolean ok = spawnPlayerMob(level, spawnPos, host.carriageIndex(), rng);
        LOGGER.info("[DungeonTrain] PlayerMob group-spawn (1-in-{}) anchorPIdx={} carriagePIdx={} pos={} -> {}",
            oneIn, provider.getPIdx(), host.carriageIndex(), spawnPos, ok ? "spawned" : "FAILED");
    }

    /**
     * Pick a random enclosed (non-FLATBED) carriage from the group so the mob
     * stands inside a walled interior; fall back to any non-null slot if the
     * group is somehow all flatbeds.
     */
    private static PendingContentsEntitySpawn pickHostCarriage(PendingContentsEntitySpawn[] pending,
                                                               RandomSource rng) {
        List<PendingContentsEntitySpawn> enclosed = new ArrayList<>();
        List<PendingContentsEntitySpawn> any = new ArrayList<>();
        for (PendingContentsEntitySpawn p : pending) {
            if (p == null) continue;
            any.add(p);
            if (!isFlatbed(p.variant())) enclosed.add(p);
        }
        List<PendingContentsEntitySpawn> pool = !enclosed.isEmpty() ? enclosed : any;
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    private static boolean isFlatbed(CarriageVariant variant) {
        return variant instanceof CarriageVariant.Builtin b && b.type() == CarriageType.FLATBED;
    }

    /**
     * Interior floor-centre {@link BlockPos} (shipyard coords) for a carriage at
     * {@code shipyardOrigin}. Interior origin is {@code shipyardOrigin.offset(1,1,1)}
     * (inside the walls/floor) per {@link CarriageContentsPlacer}; we centre on
     * the interior footprint and sit on its floor.
     */
    private static BlockPos interiorFloorCentre(BlockPos shipyardOrigin, CarriageDims dims) {
        BlockPos interiorOrigin = shipyardOrigin.offset(1, 1, 1);
        Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
        return interiorOrigin.offset(interior.getX() / 2, 0, interior.getZ() / 2);
    }

    /**
     * Resolve, create, initialise, tag, and add a PlayerMob at {@code floorPos}
     * (shipyard coords). Mirrors the positioning/persistence of
     * {@link CarriageContentsPlacer#spawnVariantMob} but additionally calls
     * {@code finalizeSpawn} (rolls personality + skin).
     *
     * @return {@code true} if the mob was added to the level
     */
    private static boolean spawnPlayerMob(ServerLevel level, BlockPos floorPos,
                                          int carriagePIdx, RandomSource rng) {
        Optional<EntityType<?>> typeOpt = EntityType.byString(PLAYER_MOB_ID.toString());
        if (typeOpt.isEmpty()) {
            LOGGER.warn("[DungeonTrain] PlayerMob spawn: entity '{}' not registered — is the bundled mod present?",
                PLAYER_MOB_ID);
            return false;
        }

        Entity entity;
        try {
            entity = typeOpt.get().create(level);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] PlayerMob spawn: create threw at {} pIdx={}: {}",
                floorPos, carriagePIdx, t.toString());
            return false;
        }
        if (!(entity instanceof Mob mob)) {
            LOGGER.warn("[DungeonTrain] PlayerMob spawn: '{}' did not create a Mob (got {}).",
                PLAYER_MOB_ID, entity == null ? "null" : entity.getClass().getSimpleName());
            if (entity != null) entity.discard();
            return false;
        }

        // Fresh UUID so repeated group spawns never collide on the UUID index.
        mob.setUUID(UUID.randomUUID());
        // Bottom-centre of the cell, random facing.
        Vec3 pos = Vec3.atBottomCenterOf(floorPos);
        mob.moveTo(pos.x, pos.y, pos.z, rng.nextFloat() * 360.0f, 0.0f);

        // PlayerMobEntity rolls its personality/skin/door behaviour in finalizeSpawn.
        // Tolerate a throw (mob still functions with defaults) but log it.
        try {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(floorPos), MobSpawnType.EVENT, null);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] PlayerMob spawn: finalizeSpawn threw at {} pIdx={}: {}",
                floorPos, carriagePIdx, t.toString());
        }

        // Difficulty-scaled equipment (armor + weapon, AIN-named / AIS-statted),
        // NO potion effects — same progression scale as carriage mobs. We apply
        // it directly (effects off) rather than via MobDifficultyEvents; combined
        // with never writing NBT_SPAWN_CARRIAGE_PIDX, the event can never add
        // effects to a PlayerMob. Honours the same difficulty toggles as mobs.
        if (DungeonTrainConfig.getDifficultyEnabled()
                && DifficultyApplier.isEligible(mob, DungeonTrainConfig.getDifficultyAffectsBabyMobs())) {
            int progression = DifficultyProgression.maxTravelledCarriageIndex(level);
            DifficultyApplier.apply(mob, progression, rng, false);
        }

        // REQUIRED: marks the mob as carriage contents so the train kill-ahead
        // sweep (TrainTickEvents) spares it; also opts it into the existing
        // on-train entity handlers, matching other carriage mobs.
        mob.addTag(CarriageContentsPlacer.contentsTagFor(carriagePIdx));
        mob.setPersistenceRequired();

        // Diagnostic parity with editor-placed contents entities
        // (read by ContentsEntityDiagnostics).
        CompoundTag persistent = mob.getPersistentData();
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_X, pos.x);
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Y, pos.y);
        persistent.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Z, pos.z);

        if (!level.addFreshEntity(mob)) {
            LOGGER.warn("[DungeonTrain] PlayerMob spawn: addFreshEntity rejected at {} pIdx={}",
                floorPos, carriagePIdx);
            return false;
        }
        return true;
    }
}
