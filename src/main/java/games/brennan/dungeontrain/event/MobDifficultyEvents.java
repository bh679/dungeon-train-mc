package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.TrainMembership;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

/**
 * Applies difficulty progression to DT carriage mobs at spawn time, scaled
 * by the <strong>max</strong> {@link PlayerRunState#travelledCarriageIndex()}
 * across all online players — i.e. how far the furthest-progressed player
 * has actually travelled while boarded this life. Each player's counter
 * resets on death, so a fresh respawn drops difficulty back to the next
 * highest live player (or tier 0 if alone).
 *
 * <p>Listens on server-side {@link EntityJoinLevelEvent}, filters to mobs
 * carrying the {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX} tag and
 * the {@link CarriageContentsPlacer#NBT_SPAWN_CARRIAGE_PIDX} NBT marker
 * (still used as a DT-mob discriminator even though the value is no longer
 * read for tier math), then delegates to {@link DifficultyApplier#apply}.
 * Mirrors the {@code VillagerTrainSpawnEvents} pattern — same
 * {@code loadedFromDisk()} skip and sticky-tag idempotency.</p>
 *
 * <p>Hostile-only: only mobs implementing {@link Enemy} are considered.
 * Passive and neutral carriage mobs (villagers, cows, sheep, …) keep their
 * vanilla / variant equipment and never receive carriage potion buffs;
 * villager trade rerolling is handled separately by
 * {@code VillagerTrainSpawnEvents}.</p>
 *
 * <p>Carriage variant mobs that pre-supply armor/effects via NBT are honoured —
 * the applier only fills empty equipment slots and only adds effects per the
 * configured tier's chance rolls.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MobDifficultyEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MobDifficultyEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (event.loadedFromDisk()) return;
        if (!DungeonTrainConfig.getDifficultyEnabled()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        if (!(mob instanceof Enemy)) return;
        if (!TrainMembership.isOnTrain(mob)) return;
        if (!DifficultyApplier.isEligible(mob, DungeonTrainConfig.getDifficultyAffectsBabyMobs())) return;

        CompoundTag persistent = mob.getPersistentData();
        if (!persistent.contains(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX)) return;

        int travelled = DifficultyProgression.maxTravelledCarriageIndex(serverLevel);

        // Regular carriage hostiles keep gaining gear strength past the netherite/level-50
        // material cap via the AIS per-tier stat bonus (PAST_MATERIAL_CAP: 0 at/below the
        // cap, then climbing). When disabled, gear stops improving at the cap as before
        // (NONE). applyEffects stays true — the potion-effect pass is unchanged.
        DifficultyApplier.StatScaling scaling = DungeonTrainConfig.getDifficultyScaleHostileGearPastCap()
                ? DifficultyApplier.StatScaling.PAST_MATERIAL_CAP
                : DifficultyApplier.StatScaling.NONE;
        boolean applied = DifficultyApplier.apply(mob, travelled, mob.getRandom(), true, scaling);
        if (applied && DebugFlags.logLootRolls()) {
            LOGGER.info("[DungeonTrain] Difficulty applied: uuid={} type={} maxTravelledCarriageIndex={} statScaling={}",
                    mob.getUUID(), mob.getType().getDescriptionId(), travelled, scaling);
        }
    }
}
