package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.discord.BookBoostReporter;
import games.brennan.dungeontrain.discord.BookPriorityReporter;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.narrative.BookBoostTag;
import games.brennan.dungeontrain.narrative.BookPriorityTag;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import games.brennan.dungeontrain.narrative.SharedBookReadTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Reads the relay's answer to a Priority Book spend and tells the player what happened.
 *
 * <p>Two routes carry a spend. A book signed with "Use a Priority Book" ticked answers on
 * {@code /books/submit} with a {@code priority} block ({@code {spent, balance, boostsLeft, held}} or
 * {@code {spent:false, error}}); {@link SharedBookSubmitResponses} hands those here, since the outbox
 * holds one handler per path. A spend on an already-approved book from the vote page answers on
 * {@code /books/credits/spend}: 200 {@code {ok, balance, boostsLeft}}, 409 {@code already_priority} (an outbox
 * re-send of a spend that landed, so: done), 402 {@code no_credits}, or another refusal.</p>
 *
 * <p>On success the author's copies of that book in their inventory get {@link BookPriorityTag}, so
 * the vote page shows the mark instead of offering to spend again. A player who has logged off hears
 * nothing; the next {@code /books/stats} greeting stamps the tag anyway. No-throw throughout, and an
 * older relay that sends no {@code priority} block parses to "nothing to say".</p>
 *
 * <p>A third route is the reader's: a boost of somebody else's book from its vote page answers on
 * {@code /books/credits/boost}: 200 {@code {ok, boostsLeft, repeat?}}, 402 {@code no_credits}, or another
 * refusal. There is no cap on a book's boosts. A {@code repeat} is an outbox re-send of a boost the relay
 * already charged, so it says nothing and counts nothing twice.</p>
 */
public final class PriorityBookResponses {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PriorityBookResponses() {}

    /** What the relay said about one spend. {@code bookId} is -1 when the request named none. */
    /** {@code balance} is what the player has left, in boosts (-1: a re-send, nothing new to say). */
    record Outcome(UUID player, int bookId, boolean spent, boolean held, long balance) {}

    /** What the relay said about one boost. {@code boostsLeft} is -1 when it did not say. */
    record BoostOutcome(UUID player, int bookId, Result result, long boostsLeft) {
        enum Result { BOOSTED, REPEAT, UNUSED }
    }

    /** Register the spend and boost handlers on the shared outbox. Called once at mod setup. */
    public static void register() {
        RelayOutbox.get().onResponse(BookPriorityReporter.PATH, PriorityBookResponses::handleSpend);
        RelayOutbox.get().onResponse(BookBoostReporter.PATH, PriorityBookResponses::handleBoost);
    }

    static void handleBoost(String requestBody, int status, String responseBody) {
        BoostOutcome o = parseBoost(requestBody, status, responseBody);
        if (o != null) announceBoost(o);
    }

    /** The outcome of a vote-page boost, or null on a network failure / unattributable request. */
    static BoostOutcome parseBoost(String requestBody, int status, String responseBody) {
        if (status < 0) return null;
        JsonObject req = asObject(requestBody);
        if (req == null) return null;
        UUID player = uuidOf(optString(req, "uuid"));
        if (player == null) return null;
        int id = (int) optLong(req, "id", -1L);
        JsonObject resp = asObject(responseBody);
        long left = resp == null ? -1L : optLong(resp, "boostsLeft", -1L);
        if (status / 100 == 2) {
            BoostOutcome.Result r = resp != null && optBool(resp, "repeat")
                ? BoostOutcome.Result.REPEAT : BoostOutcome.Result.BOOSTED;
            return new BoostOutcome(player, id, r, left);
        }
        return new BoostOutcome(player, id, BoostOutcome.Result.UNUSED, left);
    }

    /** The chat line for a boost outcome, or null when there is nothing new to say. Package-private for tests. */
    static Component lineFor(BoostOutcome o, String locale) {
        return switch (o.result()) {
            case BOOSTED -> o.boostsLeft() >= 0
                ? PriorityBookMessage.boosted(locale, o.boostsLeft()) : PriorityBookMessage.boostedNoCount();
            case REPEAT -> null; // the player heard about it when it first landed
            case UNUSED -> PriorityBookMessage.unused();
        };
    }

    /** Post onto the server thread: count the boost on the reader's copies, then say one line. */
    private static void announceBoost(BoostOutcome o) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(o.player());
                    if (player == null) return;
                    if (o.result() == BoostOutcome.Result.BOOSTED) countBoost(player, o.bookId());
                    Component line = lineFor(o, WorldInfoReporter.clientLanguage(player));
                    if (line != null) player.sendSystemMessage(line);
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] priority-book boost notice failed: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] priority-book boost notice could not be scheduled: {}", t.toString());
        }
    }

    /** One more boost on the first copy of {@code bookId} the reader holds: the one they boosted from. */
    private static void countBoost(ServerPlayer player, int bookId) {
        if (bookId <= 0) return;
        // Held first, in the packet's own order (main hand, then off hand).
        ItemStack main = player.getMainHandItem();
        if (sameId(main, bookId)) { BookBoostTag.increment(main); return; }
        for (ItemStack stack : player.getInventory().offhand) {
            if (sameId(stack, bookId)) { BookBoostTag.increment(stack); return; }
        }
        for (ItemStack stack : player.getInventory().items) {
            if (sameId(stack, bookId)) { BookBoostTag.increment(stack); return; }
        }
    }

    private static boolean sameId(ItemStack stack, int bookId) {
        OptionalInt id = SharedBookReadTag.readId(stack);
        return id.isPresent() && id.getAsInt() == bookId;
    }

    /** {@code /books/submit} answered: act on its {@code priority} block, if the upload asked for one. */
    static void handleSubmit(String requestBody, int status, String responseBody) {
        Outcome o = parseSubmit(requestBody, status, responseBody);
        if (o != null) announce(o);
    }

    static void handleSpend(String requestBody, int status, String responseBody) {
        Outcome o = parseSpend(requestBody, status, responseBody);
        if (o != null) announce(o);
    }

    /** The outcome of a sign-time spend, or null when the upload never asked or the answer says nothing. */
    static Outcome parseSubmit(String requestBody, int status, String responseBody) {
        if (status / 100 != 2) return null;
        JsonObject req = asObject(requestBody);
        if (req == null || !optBool(req, "priority")) return null;
        JsonObject resp = asObject(responseBody);
        if (resp == null || !resp.has("priority") || !resp.get("priority").isJsonObject()) return null;
        UUID player = uuidOf(optString(req, "uuid"));
        if (player == null) return null;
        JsonObject p = resp.getAsJsonObject("priority");
        int id = (int) optLong(resp, "id", -1L);
        return new Outcome(player, id, optBool(p, "spent"), optBool(p, "held"), boostsLeft(p));
    }

    /** The outcome of a vote-page spend, or null on a network failure / unattributable request. */
    static Outcome parseSpend(String requestBody, int status, String responseBody) {
        if (status < 0) return null;
        JsonObject req = asObject(requestBody);
        if (req == null) return null;
        UUID player = uuidOf(optString(req, "uuid"));
        if (player == null) return null;
        int id = (int) optLong(req, "id", -1L);
        JsonObject resp = asObject(responseBody);
        if (status / 100 == 2) {
            return new Outcome(player, id, true, false, resp == null ? 0L : boostsLeft(resp));
        }
        String error = resp == null ? null : optString(resp, "error");
        if (status == 409 && "already_priority".equals(error)) {
            return new Outcome(player, id, true, false, -1L); // a re-send of a spend that landed
        }
        return new Outcome(player, id, false, false, 0L);
    }

    /** Post onto the server thread: stamp the author's copies, then say one line. */
    private static void announce(Outcome o) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(o.player());
                    if (player == null) return;
                    if (o.spent()) stampCopies(player, o.bookId());
                    Component line = lineFor(o, WorldInfoReporter.clientLanguage(player));
                    if (line != null) player.sendSystemMessage(line);
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] priority-book notice failed: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] priority-book notice could not be scheduled: {}", t.toString());
        }
    }

    /** The chat line for an outcome, or null when there is nothing new to say. Package-private for tests. */
    static Component lineFor(Outcome o, String locale) {
        if (!o.spent()) return PriorityBookMessage.unused();
        if (o.held()) return PriorityBookMessage.held();
        // -1: an outbox re-send of a spend that already landed. The player heard about it then.
        if (o.balance() < 0) return null;
        return PriorityBookMessage.spent(locale, o.balance());
    }

    private static void stampCopies(ServerPlayer player, int bookId) {
        if (bookId <= 0) return;
        for (ItemStack stack : player.getInventory().items) stampIfMatch(stack, bookId);
        for (ItemStack stack : player.getInventory().offhand) stampIfMatch(stack, bookId);
    }

    private static void stampIfMatch(ItemStack stack, int bookId) {
        OptionalInt id = SharedBookReadTag.readId(stack);
        if (id.isPresent() && id.getAsInt() == bookId) BookPriorityTag.stamp(stack, true);
    }

    /**
     * What the player has left, in boosts: the relay's {@code boostsLeft}, or an older relay's whole
     * {@code balance} times ten. Every count the player sees is in boosts.
     */
    private static long boostsLeft(JsonObject o) {
        long boosts = optLong(o, "boostsLeft", -1L);
        return boosts >= 0 ? boosts : optLong(o, "balance", 0L) * BOOSTS_PER_BOOK;
    }

    private static final long BOOSTS_PER_BOOK = 10L;

    private static UUID uuidOf(String raw) {
        if (raw == null) return null;
        String hex = raw.replace("-", "").trim();
        if (hex.length() != 32) return null;
        try {
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject asObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static boolean optBool(JsonObject o, String key) {
        JsonElement el = o.get(key);
        try {
            return el != null && el.isJsonPrimitive() && el.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static long optLong(JsonObject o, String key, long fallback) {
        JsonElement el = o.get(key);
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsLong() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
