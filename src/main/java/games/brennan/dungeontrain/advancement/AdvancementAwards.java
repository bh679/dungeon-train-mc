package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.net.relay.AdvancementAwardClient;
import games.brennan.dungeontrain.net.relay.AdvancementAwardClient.Award;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Advancements the developer gave a player (relay: {@code advaward.js}) — an earn the game should have
 * granted and didn't, such as a Knockback Slime hit before Slimed could open its own tab.
 *
 * <p><b>A real earn.</b> Each is granted with {@link PlayerAdvancements#award}, criterion by criterion,
 * so {@code AdvancementEarnEvent} fires exactly as for an earn in play: the toast and chat line, the
 * profile bank, and the relay's first-ever claim (with its passenger-log post when it is a first).</p>
 *
 * <p><b>Clean runs only.</b> A Free Play run would earn it in that world but never bank or claim it, and
 * the ack would spend the award. So in Free Play nothing is granted and it stays pending for a clean run.</p>
 *
 * <p><b>Acked once earned.</b> The relay serves an award until it is acked, then stops. An award is acked
 * only when this world now holds the advancement — granted just now or already done. One the game refused
 * (the AFK gate, say) stays pending for the next login. One whose advancement isn't loaded (the relay
 * disabled it) is left pending too, so it lands once it is enabled again.</p>
 *
 * <p><b>Order.</b> Login, after the sidecar replay ({@code AchievementEvents#onPlayerLoggedIn}), so an
 * award already held on another world is simply "already done" and the earn is genuine (not a replay).
 * Nothing is cached: an award needs the relay anyway, for its first-ever claim.</p>
 */
public final class AdvancementAwards {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AdvancementAwards() {}

    /** Ask the relay for {@code player}'s pending awards and grant them if they are still online. */
    public static void fetchAndApply(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        AdvancementAwardClient.fetch(uuid, awards -> {
            if (!awards.isEmpty()) server.execute(() -> onFetched(server, uuid, awards));
        });
    }

    private static void onFetched(MinecraftServer server, UUID uuid, List<Award> awards) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) return;
            if (RunIntegrity.isCheated(player)) {
                LOGGER.info("[DungeonTrain] Advancement awards: {} pending for {}, held for a clean run (Free Play)",
                    awards.size(), player.getName().getString());
                return;
            }
            List<String> held = applyTo(player, awards);
            if (!held.isEmpty()) TabGateways.sync(player);
            AdvancementAwardClient.ack(uuid, held);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Advancement awards: apply after fetch failed: {}", e.toString());
        }
    }

    /** Grant {@code awards} to {@code player}. Returns the ids this world now holds — what gets acked. */
    static List<String> applyTo(ServerPlayer player, List<Award> awards) {
        MinecraftServer server = player.getServer();
        if (server == null) return List.of();
        PlayerAdvancements advancements = player.getAdvancements();
        List<String> held = new ArrayList<>();
        for (Award a : awards) {
            AdvancementHolder holder = server.getAdvancements().get(a.adv());
            if (holder == null) continue;
            boolean wasDone = advancements.getOrStartProgress(holder).isDone();
            if (!wasDone) {
                for (String criterion : holder.value().criteria().keySet()) advancements.award(holder, criterion);
            }
            if (advancements.getOrStartProgress(holder).isDone()) {
                held.add(a.id());
                LOGGER.info("[DungeonTrain] Advancement award {} {}: {} for {}",
                    a.id(), wasDone ? "already held" : "granted", a.adv(), player.getName().getString());
            } else {
                LOGGER.info("[DungeonTrain] Advancement award {} not granted yet: {} for {}",
                    a.id(), a.adv(), player.getName().getString());
            }
        }
        return held;
    }
}
