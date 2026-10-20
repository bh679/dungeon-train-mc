package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Keeps ambient spawns out of a train carriage's volume.
 *
 * <p>Vanilla natural spawning looks for room in the main level only, and a carriage is a Sable
 * sub-level, so the space it fills reads as empty. The first solid block down a column under a
 * train is the track bed, and a mob placed there stands inside the carriage floor with its head
 * through the deck (the same blind spot {@code NetherMobSpawner} guards against for its own
 * spawns).</p>
 *
 * <p><b>Ambient spawns only</b> — {@link MobSpawnType#NATURAL} and
 * {@link MobSpawnType#CHUNK_GENERATION}, the pair {@code PortalRoomSpawnGuard} uses. Spawn eggs,
 * commands and carriage contents are deliberate and untouched.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainSpawnGuard {

    private TrainSpawnGuard() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;

        ServerLevel level = event.getLevel().getLevel();
        Mob mob = event.getEntity();
        AABB box = mob.getType().getDimensions()
            .makeBoundingBox(event.getX(), event.getY(), event.getZ());
        if (CarriageSpace.overlapsAnyCarriage(level, box)) {
            event.setSpawnCancelled(true);
        }
    }
}
