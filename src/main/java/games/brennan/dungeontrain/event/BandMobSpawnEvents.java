package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
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
 *   <li><b>Upside-down band:</b> plays as a lit, safe "flipped daytime overworld" (the mirrored
 *       ceiling's underside is genuinely dark to the engine, since real skylight stays top-down), so
 *       ambient <em>hostile</em> spawns ({@link Monster}) in the band are cancelled while passive
 *       animals are left alone.</li>
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

        // Upside-down band: keep it lit and safe by cancelling ambient hostile spawns; passives stay.
        if (event.getEntity() instanceof Monster && UpsideDownBand.isInBand(level, x)) {
            event.setSpawnCancelled(true);
        }
    }
}
