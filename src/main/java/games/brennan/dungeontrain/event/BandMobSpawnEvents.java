package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
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
 *       cancelled — as well as in its entry lead-in and exit-fade transition zones, whose terrain
 *       is the same partial/composited mirror and just as unreliable to stand on.</li>
 *   <li><b>Spheres band:</b> open void with floating spheres — a mob spawned on a sphere's cap or
 *       on the fade's crumbling ground walks or falls off into the void. Every ambient spawn across
 *       the entry fade and the core is cancelled, as in the upside-down band.</li>
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

        // Upside-down band (plus its entry lead-in and exit-fade transition zones): the mirrored /
        // partial terrain gives ambient mobs nothing to stand on, so they just fall to their death —
        // cancel every natural spawn (hostile and passive) across all three.
        if (UpsideDownBand.isInBandEntryLeadOrExit(level, x)) {
            event.setSpawnCancelled(true);
            return;
        }

        // Spheres band (entry fade + core): floating spheres over open void — nothing ambient can
        // stay on its feet for long, so cancel every natural spawn here too.
        if (SpheresBand.voidRamp(level, x) > 0.0) {
            event.setSpawnCancelled(true);
        }
    }
}
