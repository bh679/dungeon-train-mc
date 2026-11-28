package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PerkLivesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Keeps perk state and effects in step with the player entity's lifecycle:
 * <ul>
 *   <li>login: load this difficulty's saved unlocks ({@link PerkProfiles}), sync the client, re-apply
 *       effects on the fresh entity;</li>
 *   <li>death: record the perks this life activated ({@link PerkProfiles#onLifeEnded}), for the nap
 *       sky's past-life paths; login and respawn send that history to the client;</li>
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
        PerkProfiles.onLogin(player);
        Perks.sync(player);
        PerkBalances.sync(player);
        syncLives(player);
        Perks.playerReady(player);
        LOGGER.debug("[DungeonTrain] perks on login for {}: {}", player.getScoreboardName(),
            Perks.describe(Perks.state(player)));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PerkProfiles.onLogout(player);
    }

    /** Once a second: a player who has just gone Free Play gets their Free Play save's unlocks. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0 || !PerksGate.isOn()) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PerkProfiles.checkFreePlay(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.isEndConquered()) {
            Perks.clearActive(player);
        }
        Perks.sync(player);
        PerkBalances.sync(player);
        syncLives(player);
        Perks.playerReady(player);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Perks.sync(player);
        PerkBalances.sync(player);
        Perks.playerReady(player);
    }

    /** A life ends: remember its perks before respawn clears them. A cancelled death (a totem) is no death. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            PerkProfiles.onLifeEnded(player);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] could not record the perks of {}'s life: {}", player.getScoreboardName(), e.toString());
        }
    }

    /** Send the past lives' perks; nothing while perks are off (the client then keeps the plain sky). */
    private static void syncLives(ServerPlayer player) {
        if (!PerksGate.isOn()) return;
        DungeonTrainNet.sendTo(player, new PerkLivesPacket(PerkProfiles.pastLives(player)));
    }
}
