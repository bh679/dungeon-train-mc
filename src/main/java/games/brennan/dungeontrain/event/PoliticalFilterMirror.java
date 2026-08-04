package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's Political Filter preference. The client is the
 * authoritative store ({@code ClientDisplayConfig.POLITICAL_FILTER}); it seeds this mirror on login
 * and on change via {@link games.brennan.dungeontrain.net.PoliticalFilterSyncPacket}.
 *
 * <p>Read by {@link games.brennan.dungeontrain.narrative.SharedBookSelector} when resolving which
 * community book a chest placeholder becomes for a given player, and by
 * {@code WorldLanguage.hostPoliticalFilter} for the world-scoped lectern path.</p>
 *
 * <h3>Why the unknown case is not simply "false"</h3>
 * <p>{@link NetworkConsentMirror} fails closed at {@code false} because there "unknown" means "don't
 * upload their writing" — the safe answer is the same for everyone. Here it isn't: {@code false}
 * means "show them everything", which is the wrong default for exactly the players this exists to
 * protect. So an unknown player falls back to the SAME locale-derived default the client applies to
 * {@code UNSET} ({@code PoliticalFilterPrefs.isEnabled}) — a Chinese-language client filters, everyone
 * else does not — using the locale vanilla already syncs in {@code ClientInformation}. That covers the
 * window before the login packet lands, and a client too old to send the packet at all.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PoliticalFilterMirror {

    /** Per-player preference, seeded from the client sync. Absent = fall back to the player's locale. */
    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private PoliticalFilterMirror() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(ServerPlayer player, boolean enabled) {
        if (player == null) return;
        ENABLED.put(player.getUUID(), enabled);
    }

    /**
     * Whether politically-tagged community content should be withheld from {@code player}. Falls back
     * to the locale-derived default when the client hasn't synced — see the class javadoc.
     */
    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) return false;
        Boolean synced = ENABLED.get(player.getUUID());
        if (synced != null) return synced;
        return defaultForLocale(WorldInfoReporter.clientLanguage(player));
    }

    /**
     * The unsynced default for a raw client locale: filter for the Chinese language family, not
     * otherwise. Mirrors {@code PoliticalFilterPrefs.isEnabled}'s handling of {@code UNSET} — the two
     * must agree, or a player's first moments in a world would differ from every moment after.
     */
    static boolean defaultForLocale(String locale) {
        return locale != null && !locale.isBlank() && "zh".equals(LanguageFamily.of(locale));
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
