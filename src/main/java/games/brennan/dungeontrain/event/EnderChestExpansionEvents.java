package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.EnderChestExpansion;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Restores an expanded Ender Chest's capacity at login — see {@link EnderChestExpansion#applyStored}.
 *
 * <p>HIGHEST priority on purpose: EnderChestPersistence restores the stored chest into the live
 * container at NORMAL, and a 27-slot container drops every stack past slot 27 on the way in. The
 * capacity has to be right before that runs. Deciding it needs only the player's own record, not the
 * Free Play verdict, which is what lets this run ahead of the cheat scan.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EnderChestExpansionEvents {

    private EnderChestExpansionEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EnderChestExpansion.applyStored(player);
    }
}
