package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Tells {@link EnderChestLockBridge} when EnderChestPersistence holds a player, so a live Ender Chest
 * swap can never run while ECP would have to guess which slot the chest holds.
 *
 * <ul>
 *   <li><b>Login, LOWEST</b> — after ECP's own restore (NORMAL) and after every DT login handler, some
 *       of which flip Free Play (HIGHEST). Settling here also applies whatever those flips decided.</li>
 *   <li><b>Logout, HIGHEST</b> — before ECP saves and evicts the player, and before DT's own logout
 *       refreshes ({@code OperatorIntegrity#onPlayerLogout}) can ask for a swap.</li>
 * </ul>
 *
 * <p>Holds no reference to ECP itself, so it is harmless when ECP is absent: the bridge's
 * {@code installed} gate still says no.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EnderChestLockEvents {

    private EnderChestLockEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnderChestLockBridge.settle(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EnderChestLockBridge.release(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EnderChestLockBridge.releaseAll();
    }
}
