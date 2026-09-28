package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.spawn.AmbientDensity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * A ceiling on how many ambient monsters may stand around one player at a time.
 *
 * <p><b>Why the world needs telling.</b> Vanilla's monster cap is level-wide and assumes the world is
 * deep enough to spend it somewhere else — see {@link AmbientDensity} for the measurement. A Dungeon
 * Train overworld is not, so the whole allowance lands on whoever is playing. This is the fourth
 * ambient-spawn guard, alongside {@code BandMobSpawnEvents} (per-band rules),
 * {@link BasementSpawnGuard} (nothing under the floor) and {@code PortalRoomSpawnGuard} (nothing
 * inside a room) — and the only one that asks about density rather than place.</p>
 *
 * <p><b>Ambient only</b>, the same {@link MobSpawnType#NATURAL} / {@link MobSpawnType#CHUNK_GENERATION}
 * pair the other three treat as ambient. Everything deliberate is untouched: the train's own mobs,
 * commands, spawn eggs, breeding, conversion, a raid. And <b>monsters only</b> — animals have their
 * own much smaller vanilla cap and were never the problem.</p>
 *
 * <p><b>Off by configuration, not by accident.</b> {@code ambientMonsterCap = 0} disables the guard
 * outright, and it never fires in a world with nobody near the spawn position.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AmbientDensityGuard {

    private AmbientDensityGuard() {}

    /**
     * Refresh the per-player counts on the cadence, so the guard itself is a map lookup.
     *
     * <p>Overworld-only like the guard below: a Sable sub-level is a level of its own and the mobs
     * riding one are the train's, which are persistent and never counted anyway.</p>
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (DungeonTrainCommonConfig.getAmbientMonsterCap() <= 0) return;
        if (level.getGameTime() % AmbientDensity.REFRESH_TICKS != 0) return;

        AmbientDensity.refresh(level);
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;
        if (event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;

        int cap = DungeonTrainCommonConfig.getAmbientMonsterCap();
        if (cap <= 0) return;

        ServerLevel level = event.getLevel().getLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        int nearby = AmbientDensity.nearestCount(level, event.getX(), event.getY(), event.getZ());
        if (AmbientDensity.atCap(nearby, cap)) {
            event.setSpawnCancelled(true);
        }
    }

    /** A player who left has no allowance to remember. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        AmbientDensity.forget(event.getEntity().getUUID());
    }
}
