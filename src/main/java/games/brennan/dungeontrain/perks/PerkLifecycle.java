package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * Keeps perk state and effects in step with the player entity's lifecycle:
 * <ul>
 *   <li>login: sync the client, re-apply effects on the fresh entity;</li>
 *   <li>respawn after death: clear active points (unlocked set survives via
 *       {@code copyOnDeath}), sync, re-apply; the End-conquered respawn is not a death;</li>
 *   <li>dimension change: the attachment is copied but the client holder is not, so re-sync and
 *       re-apply (attribute modifiers move with the entity, so re-applying is idempotent).</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerkLifecycle {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerkLifecycle() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PerkRegistry.bootstrap();
        Perks.sync(player);
        PerkBalances.sync(player);
        Perks.playerReady(player);
        LOGGER.debug("[DungeonTrain] perks on login for {}: {}", player.getScoreboardName(),
            Perks.describe(Perks.state(player)));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.isEndConquered()) {
            Perks.clearActive(player);
        }
        Perks.sync(player);
        PerkBalances.sync(player);
        Perks.playerReady(player);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Perks.sync(player);
        PerkBalances.sync(player);
        Perks.playerReady(player);
    }
}
