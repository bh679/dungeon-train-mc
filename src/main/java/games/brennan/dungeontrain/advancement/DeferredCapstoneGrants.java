package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The live capstone check (each tab's tab-complete, then the Everything Burrito), run a moment after
 * the earn that may have finished it rather than inside that earn.
 *
 * <p>Granted inside the earn event, the capstone and the advancement that completed it change in the
 * same tick, so vanilla sends both in one update packet built from a {@code HashMap}, and the client
 * adds their cards in that map's order: the capstone's card came first about half the time. Checked
 * {@link #DELAY_TICKS} later it rides a packet of its own, after the card it crowns. The burrito
 * follows the same way: a check that grants a tab-complete leaves the burrito for the next one, which
 * that tab-complete's own earn schedules, so the burrito lands last of all.</p>
 *
 * <p>Login and difficulty swaps still check at once ({@code AdvancementProfiles}): those are backfills
 * with no card to follow. In memory only; a player who leaves before the check is caught by the login
 * one. Server thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeferredCapstoneGrants {

    /** Half a second: the completing card is already up, and the chat line lands after its own. */
    public static final int DELAY_TICKS = 10;

    private static final Due PENDING = new Due();

    private DeferredCapstoneGrants() {}

    /** Check {@code player}'s capstones {@link #DELAY_TICKS} from now; a later earn pushes it back. */
    public static void schedule(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PENDING.schedule(player.getUUID(), server.getTickCount() + DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (UUID id : PENDING.drainDue(server.getTickCount())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.hasDisconnected()) continue;
            // A tab-complete granted now is the burrito's last requirement at most: its own earn
            // schedules the next check, so the burrito gets a packet of its own after it.
            if (!TabCompleteAdvancements.checkAndGrant(player)) CompletionistAdvancement.checkAndGrant(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    /** Player → tick the check is due. Pure; package-private for tests. */
    static final class Due {
        private final Map<UUID, Long> dueAt = new HashMap<>();

        void schedule(UUID player, long tick) {
            dueAt.merge(player, tick, Math::max);
        }

        boolean isEmpty() {
            return dueAt.isEmpty();
        }

        List<UUID> drainDue(long now) {
            List<UUID> due = new ArrayList<>();
            dueAt.forEach((id, tick) -> { if (tick <= now) due.add(id); });
            due.forEach(dueAt::remove);
            return due;
        }

        void clear() {
            dueAt.clear();
        }
    }
}
