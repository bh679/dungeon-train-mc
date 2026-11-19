package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriagePets;
import games.brennan.dungeontrain.world.PendingPets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Hands {@link PendingPets} back to their owner a few seconds after they are in the world — late
 * enough that the join has settled them onto the train, so a pet does not land beside a player who
 * is still being placed.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PendingPetsEvents {

    /** Ticks after joining (or respawning) before waiting pets are handed over. */
    static final int DELIVER_AFTER_TICKS = 100;

    private PendingPetsEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Once a second from the delivery point on: a map lookup, nothing more, for anyone with no pets waiting.
        if (player.tickCount < DELIVER_AFTER_TICKS || player.tickCount % 20 != 0) return;
        if (!player.isAlive() || player.isSpectator()) return;
        PendingPets pending = PendingPets.get(player.serverLevel());
        if (!pending.has(player.getUUID())) return;
        for (CompoundTag pet : pending.take(player.getUUID())) {
            CarriagePets.respawnBeside(pet, player);
        }
    }
}
