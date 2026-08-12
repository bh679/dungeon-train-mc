package games.brennan.dungeontrain.progress;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's relay progress credential, seeded on login by
 * {@link games.brennan.dungeontrain.net.ProgressAccountSyncPacket}.
 *
 * <p>The client is authoritative: the credential lives in a client-scope file, so the server can only
 * know it if the client sends it. Structurally this is
 * {@link games.brennan.dungeontrain.event.NetworkConsentMirror}'s sibling — a concurrent map, cleared
 * per-player on logout and wholesale on server stop.</p>
 *
 * <p><b>Fail-closed</b>: {@link #secretFor} returns {@code null} for a player who has not synced, so a
 * missing signal means "local-only progress", never a guess at whose account to write to.</p>
 *
 * <p>Why a secret crosses the wire at all: the Ender Chest lives server-side, on the
 * {@code PlayerEnderChestContainer}, so the sync has to run there. In phase 1 the packet is only sent
 * when the player's own client owns the server (single-player / LAN host), so the secret never leaves
 * the player's machine — see {@code ProgressSyncClient}. A remote dedicated server would be handed a
 * credential it could impersonate the player with, which is why that path is opt-in and off by
 * default.</p>
 */
public final class ProgressAccounts {

    /** Player uuid → bearer secret. Absent = no credential = local-only progress for that player. */
    private static final Map<UUID, String> SECRETS = new ConcurrentHashMap<>();

    private ProgressAccounts() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(ServerPlayer player, String secret) {
        if (player == null) return;
        if (secret == null || secret.isBlank()) {
            SECRETS.remove(player.getUUID());
        } else {
            SECRETS.put(player.getUUID(), secret);
        }
    }

    /** The credential for {@code player}, or {@code null} when none has been synced. */
    public static String secretFor(ServerPlayer player) {
        return player == null ? null : SECRETS.get(player.getUUID());
    }

    /** Drop one player's credential (logout). */
    public static void forget(UUID playerUuid) {
        if (playerUuid != null) SECRETS.remove(playerUuid);
    }

    /** Drop every credential (server stop), so a new world starts clean and clients re-seed on login. */
    public static void clear() {
        SECRETS.clear();
    }
}
