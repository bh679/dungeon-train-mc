package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayReconcile;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.net.BuilderReconcileOfferPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks the relay, once per join, whether it still has this install's builds — and offers to put back
 * the ones it doesn't.
 *
 * <p>At join rather than continuously: the check is a relay round trip, and a build that has been
 * evicted stays evicted, so there is nothing to gain from asking again mid-session. See
 * {@link BuilderRelayReconcile} for why anything is missing in the first place.</p>
 *
 * <p>Nothing is uploaded here. This only decides whether the player is worth asking; the answer comes
 * back as {@code BuilderReconcileStartPacket}. A player who is not asked — no consent, no recorded
 * uploads, a relay that cannot be reached — is not told anything either, because there is nothing
 * they could do about it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuilderReconcileEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Who has already been asked this session.
     *
     * <p>Keyed by uuid and never cleared on logout: rejoining twice in one session is a reconnect, and
     * being asked the same question on each of them is worse than being asked once. A restart clears
     * it, which is the right cadence for a question about data loss.</p>
     */
    private static final Set<UUID> asked = ConcurrentHashMap.newKeySet();

    private BuilderReconcileEvents() {}

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null || !BuilderRelayUpload.canUpload(player)) return;
        if (!asked.add(player.getUUID())) return;

        // The overworld's saved data holds the relay records, as it does for every other builder
        // profile path.
        ServerLevel level = server.overworld();
        BuilderRelayReconcile.scan(player, level).thenAccept(scan -> {
            if (!scan.reachable() || scan.isEmpty()) return;
            server.execute(() -> {
                if (player.hasDisconnected()) return;
                LOGGER.info("[DungeonTrain] Build reconcile: offering {} on-disk and {} backup-only "
                        + "build(s) to {}", scan.onDisk().size(), scan.backupOnly().size(),
                        player.getGameProfile().getName());
                PacketDistributor.sendToPlayer(player,
                        new BuilderReconcileOfferPacket(scan.onDisk().size(), scan.backupOnly().size()));
            });
        });
    }
}
