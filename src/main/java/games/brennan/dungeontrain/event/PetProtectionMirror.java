package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's "protect my pets" preference. The client is the
 * authoritative store ({@code ClientDisplayConfig.PROTECT_OWN_PETS}); it seeds this mirror on login
 * and on change via {@link games.brennan.dungeontrain.net.PetProtectionSyncPacket}.
 *
 * <p>Read by {@link OwnPetProtectionEvents} on every hit a player lands on an animal they own.</p>
 *
 * <p>Unknown = {@code true}, the opposite of {@link BookAuthorChatMirror}: this option is on by
 * default, so a player whose sync hasn't landed (or whose client is too old to send it) keeps the
 * protection rather than losing it for a few ticks.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PetProtectionMirror {

    /** Per-player preference, seeded from the client sync. Absent = on. */
    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private PetProtectionMirror() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(Player player, boolean enabled) {
        if (player == null) return;
        ENABLED.put(player.getUUID(), enabled);
    }

    /** Whether {@code player}'s own pets are safe from them. Unsynced players: yes. */
    public static boolean isEnabled(Player player) {
        if (player == null) return true;
        return !Boolean.FALSE.equals(ENABLED.get(player.getUUID()));
    }

    /** Drop a player's mirrored preference when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            ENABLED.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        ENABLED.clear();
    }
}
