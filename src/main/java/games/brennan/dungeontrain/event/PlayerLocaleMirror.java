package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's selected game language (the Minecraft
 * {@code options.languageCode}, e.g. {@code "en_us"}, {@code "de_de"}, {@code "pt_br"}). The client
 * is authoritative — the language is a CLIENT-scope setting — and seeds this mirror on login via
 * {@link games.brennan.dungeontrain.net.PlayerLocaleSyncPacket}.
 *
 * <p>Used when stamping player-written content uploaded server-side (community shared books via
 * {@code SharedBookReporter}, lectern letters via {@code LetterReporter}) with the author's language,
 * so the relay can store it and deliver language-matched content. <b>Fail-open:</b>
 * {@link #get(ServerPlayer)} returns {@code null} when no sync has arrived (a client without Dungeon
 * Train's locale packet, or before its login sync lands); a null language never blocks an upload — the
 * content is simply stored without a language tag.</p>
 *
 * <p>Mirrors the structure of {@link NetworkConsentMirror}: a concurrent map (the sync packet handler
 * hops to the server thread, but concurrent is cheap insurance), cleared per-player on logout
 * ({@link #forget}, called from {@link PlayerJoinEvents}) and wholesale on server stop (so a new world
 * starts clean and each client re-seeds on its next login).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerLocaleMirror {

    /** Per-player language code, seeded from the client login sync. Absent = not-yet-known = null. */
    private static final Map<UUID, String> LOCALES = new ConcurrentHashMap<>();

    private PlayerLocaleMirror() {}

    /**
     * Seed / update the mirror from the client's sync packet. Server thread. A blank/{@code null}
     * language is stored as {@code null} (treated as unknown) rather than an empty tag.
     */
    public static void set(ServerPlayer player, String langCode) {
        if (player == null) return;
        set(player.getUUID(), langCode);
    }

    /**
     * UUID-based seed core, package-private so the fail-open cleaning can be unit-tested without a
     * running server. A blank/{@code null} language is stored as {@code null} (treated as unknown).
     */
    static void set(UUID playerId, String langCode) {
        if (playerId == null) return;
        String cleaned = langCode == null || langCode.isBlank() ? null : langCode.trim();
        if (cleaned == null) {
            LOCALES.remove(playerId);
        } else {
            LOCALES.put(playerId, cleaned);
        }
    }

    /**
     * The player's synced language code, or {@code null} when the client hasn't synced one yet.
     * Fail-open: a {@code null} argument or unknown player returns {@code null}.
     */
    public static String get(ServerPlayer player) {
        if (player == null) return null;
        return LOCALES.get(player.getUUID());
    }

    /** The player's synced language code by UUID, or {@code null} when unknown. */
    public static String get(UUID playerId) {
        if (playerId == null) return null;
        return LOCALES.get(playerId);
    }

    /** Drop a player's mirrored language when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            LOCALES.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        LOCALES.clear();
    }
}
