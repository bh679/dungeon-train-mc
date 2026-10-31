package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.PendingPerks;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Lands the perk unlocks carried across Board anew ({@link PendingPerks}) on the new world's first
 * login. Runs ahead of {@link PerkLifecycle#onLogin}, which then syncs the client and applies effects.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerkCarryEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerkCarryEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PendingPerks.isPresentFor(player.getUUID())) return;
        Map<String, Integer> carried = PendingPerks.take(player);
        if (carried.isEmpty()) return;
        PerkRegistry.bootstrap();
        Map<String, Integer> merged = PendingPerks.merged(Perks.state(player).unlocks(), carried);
        int applied = 0;
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            if (!PerkRegistry.isKnown(e.getKey())) continue; // a perk this build no longer has
            if (Perks.state(player).unlockLevel(e.getKey()) == e.getValue()) continue;
            Perks.setUnlockLevel(player, e.getKey(), e.getValue());
            applied++;
        }
        LOGGER.info("[DungeonTrain] perk carry: {} unlock level(s) restored for {}", applied, player.getName().getString());
    }
}
