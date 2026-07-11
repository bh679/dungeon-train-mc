package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Per-band ambient-spawn rules. Only affects <em>ambient</em>
 * ({@link MobSpawnType#NATURAL} / {@link MobSpawnType#CHUNK_GENERATION}) spawns in the overworld —
 * the train's own scripted spawns (PlayerMobs, carriage contents, commands, etc., which use other
 * spawn types) are never touched.
 *
 * <ul>
 *   <li><b>Disintegration/End band:</b> the world is the End, so only endermen belong — every other
 *       ambient mob whose position falls in the band's world-X range is cancelled.</li>
 *   <li><b>Upside-down band:</b> the terrain is a vertical mirror — the "ground" is a ceiling with
 *       an open gap at the train — so any naturally-spawned mob has nothing to stand on and simply
 *       falls to its death. Every ambient spawn (hostile <em>and</em> passive) in the band is
 *       cancelled.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BandMobSpawnEvents {

    private BandMobSpawnEvents() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;

        ServerLevel level = event.getLevel().getLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        int x = (int) Math.floor(event.getX());

        // End band: only endermen (the End's natives) survive; cancel every other ambient spawn.
        if (DisintegrationBand.middleRampAt(level, x) > 0.0) {
            if (!(event.getEntity() instanceof EnderMan)) {
                event.setSpawnCancelled(true);
            }
            return;
        }

        // Upside-down band: the mirrored terrain gives ambient mobs nothing to stand on, so they just
        // fall to their death — cancel every natural spawn (hostile and passive) in the band.
        if (UpsideDownBand.isInBand(level, x)) {
            event.setSpawnCancelled(true);
        }
    }
}
