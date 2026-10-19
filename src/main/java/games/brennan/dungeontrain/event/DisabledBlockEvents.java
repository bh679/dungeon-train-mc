package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisabledBlockUseGate;
import games.brennan.dungeontrain.compat.DisabledRespawnPoints;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Disabled mod blocks: both sides swallow a click on one, so the client doesn't predict a menu or arm swing;
 * and a spawn point already set at one is cleared on login and before the player respawns.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisabledBlockEvents {

    private DisabledBlockEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        DisabledBlockUseGate.veto(event);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DisabledRespawnPoints.clearIfDisabled(player);
        }
    }

    /** Lowest priority, so a death another handler cancels (a totem, DT's own saves) leaves the spawn alone. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) {
            DisabledRespawnPoints.clearIfDisabled(player);
        }
    }
}
