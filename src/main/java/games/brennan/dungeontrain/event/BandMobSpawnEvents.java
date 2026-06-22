package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Inside the disintegration band the world is the End, so only endermen belong
 * there. This cancels every <em>ambient</em> ({@link MobSpawnType#NATURAL} /
 * {@link MobSpawnType#CHUNK_GENERATION}) non-enderman mob spawn whose position falls
 * in the band's world-X range — leaving natural endermen and not touching the train's
 * own scripted spawns (PlayerMobs, carriage contents, commands, etc., which use other
 * spawn types).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BandMobSpawnEvents {

    private BandMobSpawnEvents() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;
        if (event.getEntity() instanceof EnderMan) return; // endermen are the End's natives

        ServerLevel level = event.getLevel().getLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        // Only inside a void/End phase of the (repeating) band — leave the overworld stretches alone.
        int x = (int) Math.floor(event.getX());
        if (DisintegrationBand.middleRampAt(level, x) > 0.0) {
            event.setSpawnCancelled(true);
        }
    }
}
