package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.narrative.ReadingLanguages;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side copy of each player's reading languages (synced from their client by
 * {@link games.brennan.dungeontrain.net.ReadingLanguagesSyncPacket}), plus the language a player picked
 * on the sign screen for the book they are about to sign
 * ({@link games.brennan.dungeontrain.net.BookLanguagePacket}).
 *
 * <p>A player who never synced reads only their game language — the behaviour before this existed. The
 * game language itself is never stored: it is read live from the client information, so switching game
 * language needs no re-sync.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ReadingLanguagesMirror {

    private record Synced(int gameWeight, List<ReadingLanguages.Entry> extras) {}

    private static final Map<UUID, Synced> SYNCED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PENDING_BOOK_LANGUAGE = new ConcurrentHashMap<>();

    private ReadingLanguagesMirror() {}

    public static void set(ServerPlayer player, int gameWeight, List<ReadingLanguages.Entry> extras) {
        if (player == null) return;
        SYNCED.put(player.getUUID(), new Synced(ReadingLanguages.clampWeight(gameWeight),
                ReadingLanguages.clean(extras, WorldInfoReporter.clientLanguage(player))));
    }

    /** This player's extra reading languages, cleaned against their current game language. */
    public static List<ReadingLanguages.Entry> extras(ServerPlayer player) {
        if (player == null) return List.of();
        Synced s = SYNCED.get(player.getUUID());
        return s == null ? List.of() : ReadingLanguages.clean(s.extras(), WorldInfoReporter.clientLanguage(player));
    }

    /** Family → weight for the selector, game language first. */
    public static Map<String, Integer> families(ServerPlayer player) {
        Synced s = player == null ? null : SYNCED.get(player.getUUID());
        String game = WorldInfoReporter.clientLanguage(player);
        return s == null
                ? ReadingLanguages.families(game, ReadingLanguages.DEFAULT_WEIGHT, List.of())
                : ReadingLanguages.families(game, s.gameWeight(), s.extras());
    }

    /** Remember the language picked on the sign screen; consumed by the next {@link #takeBookLanguage}. */
    public static void putBookLanguage(ServerPlayer player, String locale) {
        if (player == null) return;
        String clean = ReadingLanguages.cleanLocale(locale);
        if (clean == null) PENDING_BOOK_LANGUAGE.remove(player.getUUID());
        else PENDING_BOOK_LANGUAGE.put(player.getUUID(), clean);
    }

    /**
     * The locale to file the book this player is signing under: their sign-screen pick when it is one of
     * their reading languages, else their game language. Consumes the pick.
     */
    public static String takeBookLanguage(ServerPlayer player) {
        String game = WorldInfoReporter.clientLanguage(player);
        if (player == null) return game;
        String picked = PENDING_BOOK_LANGUAGE.remove(player.getUUID());
        return ReadingLanguages.bookLocale(picked, game, extras(player));
    }

    public static void forget(UUID playerId) {
        if (playerId == null) return;
        SYNCED.remove(playerId);
        PENDING_BOOK_LANGUAGE.remove(playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SYNCED.clear();
        PENDING_BOOK_LANGUAGE.clear();
    }
}
