package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Keeps the dark from populating a portal room.
 *
 * <p>A pair's structure sits at the world floor, unlit except for what the room itself provides, and
 * far from any player most of the time — which is a textbook mob-spawning volume. Left alone, a
 * player who walked through a portal would find a room the author never built anything into slowly
 * filling with skeletons, and an endless room repeating that a hundred times over.</p>
 *
 * <p><b>Ambient spawns only.</b> Cancels {@link MobSpawnType#NATURAL} and
 * {@link MobSpawnType#CHUNK_GENERATION}, the same pair {@code BandMobSpawnEvents} treats as ambient.
 * Everything deliberate still works inside a portal room: a spawn egg, a command, a bucketed
 * axolotl, a villager breeding, a zombie converting. The rule is "nothing arrives here on its own",
 * not "nothing may ever be here" — a player who leads a mob in keeps it, and
 * {@code PortalDespawnEvents} already goes out of its way to stop that mob being reaped.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalRoomSpawnGuard {

    private PortalRoomSpawnGuard() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;

        ServerLevel level = event.getLevel().getLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        if (PortalCarriageEvents.isInsidePortalStructure(dims, event.getX(), event.getY(), event.getZ())) {
            event.setSpawnCancelled(true);
        }
    }
}
