package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.MultiplayerChallengeGate;
import games.brennan.dungeontrain.net.ChallengesLockedPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Tells clients which challenges {@link MultiplayerChallengeGate} is refusing, so the advancements
 * screen can fade them and say why. Sent on login, and to everyone whenever the lock flips.
 *
 * <p>The lock can flip from several places — a friend joining (the {@link MultiplayerState} latch),
 * Open to LAN, or a hosting mod such as Essential or e4mc publishing the world — so rather than hook
 * each, the server re-reads {@link MultiplayerChallengeGate#isLocked} once a second and broadcasts on
 * a change. That is one boolean compare per second; the packet goes out only on login and flips.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MultiplayerChallengeSync {

    private static final int CHECK_INTERVAL_TICKS = 20;

    /** The lock state last broadcast this server run; null before the first check. */
    private static Boolean lastLocked;

    private MultiplayerChallengeSync() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.server != null) {
            PacketDistributor.sendToPlayer(player, packet(player.server, MultiplayerChallengeGate.isLocked(player.server)));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
        boolean locked = MultiplayerChallengeGate.isLocked(server);
        if (lastLocked != null && lastLocked == locked) return;
        lastLocked = locked;
        PacketDistributor.sendToAllPlayers(packet(server, locked));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastLocked = null;
    }

    private static ChallengesLockedPacket packet(MinecraftServer server, boolean locked) {
        List<ResourceLocation> ids = locked ? MultiplayerChallengeGate.memberIds(server) : List.of();
        return new ChallengesLockedPacket(ids);
    }
}
