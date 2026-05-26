package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.difficulty.DifficultyApplier;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

/**
 * Applies difficulty progression to DT carriage mobs at spawn time.
 *
 * <p>Listens on server-side {@link EntityJoinLevelEvent}, filters to mobs
 * carrying the {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX} tag, reads
 * the stamped carriage index from NBT ({@link
 * CarriageContentsPlacer#NBT_SPAWN_CARRIAGE_PIDX}), and delegates to {@link
 * DifficultyApplier#apply}. Mirrors the {@link VillagerTrainSpawnEvents}
 * pattern — same {@code loadedFromDisk()} skip and sticky-tag idempotency.</p>
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
        if (level.isClientSide) return;
        if (event.loadedFromDisk()) return;
        if (!DungeonTrainConfig.getDifficultyEnabled()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        if (!isOnTrain(mob)) return;
        if (!DifficultyApplier.isEligible(mob, DungeonTrainConfig.getDifficultyAffectsBabyMobs())) return;

        CompoundTag persistent = mob.getPersistentData();
        if (!persistent.contains(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX)) return;
        int carriagePIdx = persistent.getInt(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX);

        boolean applied = DifficultyApplier.apply(mob, carriagePIdx, mob.getRandom());
        if (applied && DebugFlags.logLootRolls()) {
            LOGGER.info("[DungeonTrain] Difficulty applied: uuid={} type={} pIdx={}",
                    mob.getUUID(), mob.getType().getDescriptionId(), carriagePIdx);
        }
    }

    private static boolean isOnTrain(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }
}
