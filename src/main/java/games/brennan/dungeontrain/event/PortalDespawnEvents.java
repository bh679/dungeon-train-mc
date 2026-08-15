package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalOccupants;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;

/**
 * Keeps the dimensional carriage's occupants from being reaped while anyone is using the room.
 *
 * <p>A mob in a twin corridor is, as far as vanilla is concerned, a mob at the bottom of the world
 * with the nearest player riding a train away from it — so the distance rule discards it, and a
 * villager walked through a portal is gone before its player comes back. The room is an illusion
 * vanilla knows nothing about, so the answer has to be given here.</p>
 *
 * <p>{@link MobDespawnEvent.Result#DENY} rather than {@code Mob.setPersistenceRequired()}: it
 * cancels the distance despawn <i>and</i> resets {@code noActionTime}, which is the second way a
 * bored mob gets reaped, and it writes nothing onto the entity — protection simply lapses when the
 * sighting ages out of {@link PortalOccupants}.</p>
 *
 * <p>Runs for every despawn check in the game, so it answers from an empty registry with one
 * boolean in any world where nobody has been near a portal.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalDespawnEvents {

    private PortalDespawnEvents() {}

    @SubscribeEvent
    public static void onMobDespawn(MobDespawnEvent event) {
        if (PortalOccupants.isEmpty()) return;

        Mob mob = event.getEntity();
        if (!PortalOccupants.isProtected(mob.getId(), mob.level().getGameTime())) return;

        event.setResult(MobDespawnEvent.Result.DENY);
    }
}
