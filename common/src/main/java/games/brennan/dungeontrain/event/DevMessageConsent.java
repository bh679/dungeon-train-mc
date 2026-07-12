package games.brennan.dungeontrain.event;
import games.brennan.dungeontrain.platform.DtPlatform;

import games.brennan.dungeontrain.discord.DevMessageReport;
import games.brennan.dungeontrain.net.ConsentUpdatePacket;
import games.brennan.dungeontrain.net.platform.DtNetSender;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates a relayed Developer message behind an in-game consent handshake before it is shown in a
 * player's in-game chat. The message is always independently available in the title-screen menu
 * chat (the relay poll — see {@code client/chat}); this only governs the <em>in-game</em> surface.
 *
 * <p>The message is scoped to the single player whose Discord thread it was posted in — never
 * broadcast to everyone. Flow, for that player, when a Developer message arrives (via
 * {@link games.brennan.dungeontrain.compat.DiscordInboundBridge} → {@link #onDevMessage}):</p>
 * <ul>
 *   <li><b>Consent valid</b> → deliver the message straight to in-game chat.</li>
 *   <li><b>No valid consent</b> → hold the message and show a yellow prompt
 *       ("The Developer wants to send you a message, type @Dev to accept."), once per pending
 *       batch, and post a "consent requested" note to Discord. When the player types {@code @Dev}
 *       ({@link #onConsentAccepted}, driven from {@link MentionPresenceEvents}'s chat listener),
 *       consent is granted, the held messages are flushed to chat, and a "consent accepted" note
 *       is posted to Discord.</li>
 * </ul>
 *
 * <h3>Consent lifetime</h3>
 * <p>Once granted, consent is valid while EITHER the player is still in the world session it was
 * granted in ({@code grantSession == SESSION_ID}) OR they have messaged the dev within the last
 * {@link #CONSENT_WINDOW_MS}. It fully expires only once a new world has loaded AND 20 minutes have
 * passed with no message to the dev ("whichever comes last"). The 20-minute window slides on any
 * in-game chat after consent (stamped here from {@link MentionPresenceEvents}) and on any main-menu
 * chat send (stamped client-side).</p>
 *
 * <h3>State authority &amp; threading</h3>
 * <p>The client persistently owns the raw fields (so they survive a world reload and are fed by the
 * client-only menu chat) and seeds this server-side mirror on login via
 * {@link games.brennan.dungeontrain.net.ConsentSyncPacket}. The inbound Discord seam fires on a network thread, so
 * {@link #onDevMessage} hops to the server thread via {@link MinecraftServer#execute}; every other
 * entry point already runs on the server thread. Maps are concurrent as cheap insurance.</p>
 */
public final class DevMessageConsent {

    /** Sliding window: consent stays valid this long after the player's last message to the dev. */
    public static final long CONSENT_WINDOW_MS = 20L * 60_000L;

    /** The prompt shown in-game when a held Developer message is awaiting consent. */
    private static final String PROMPT_TEXT =
        "The Developer wants to send you a message, type @Dev to accept.";

    /** Per-player mirror of the client's persisted consent state, seeded on login and updated here. */
    private static final class Mirror {
        boolean granted;
        double grantSession;
        long lastMsgToDevMs;
    }

    private static final Map<UUID, Mirror> MIRRORS = new ConcurrentHashMap<>();

    /** Developer messages held awaiting this player's consent (delivered in order once accepted). */
    private static final Map<UUID, Deque<String>> PENDING = new ConcurrentHashMap<>();

    /**
     * Token for the current world session, set on {@link ServerStartedEvent}. A fresh integrated
     * server (a new/reloaded world) gets a new token, so consent granted in a previous session no
     * longer matches and falls back to the 20-minute window. {@code 0} until a server starts.
     */
    private static volatile double sessionId = 0.0;

    private DevMessageConsent() {}

        public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        sessionId = (double) System.currentTimeMillis();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        // Nothing leaks into the next world: the client re-seeds its state on the next login.
        MIRRORS.clear();
        PENDING.clear();
    }

    /** Drop per-player state when a player leaves; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        MIRRORS.remove(playerId);
        PENDING.remove(playerId);
    }

    /** Seed the mirror from the client's login sync ({@link games.brennan.dungeontrain.net.ConsentSyncPacket}). Server thread. */
    public static void onClientSync(ServerPlayer player, boolean granted, double grantSession, double lastMsgToDevMs) {
        Mirror m = MIRRORS.computeIfAbsent(player.getUUID(), k -> new Mirror());
        m.granted = granted;
        m.grantSession = grantSession;
        m.lastMsgToDevMs = (long) lastMsgToDevMs;
    }

    /** True if {@code playerId} currently has a valid consent to receive Developer messages in-game. */
    private static boolean isValid(UUID playerId) {
        Mirror m = MIRRORS.get(playerId);
        if (m == null) return false;
        return isValid(m.granted, m.grantSession, sessionId, m.lastMsgToDevMs, System.currentTimeMillis());
    }

    /**
     * Pure consent-validity rule (package-private for unit testing). Consent is valid while EITHER
     * it was granted in the current world session, OR the last message to the dev was within
     * {@link #CONSENT_WINDOW_MS}. It expires only once a new world has loaded AND the window has
     * lapsed — i.e. the later of the two conditions ("whichever comes last").
     *
     * @param sessionId {@code 0.0} means "no world session yet" and never counts as a same-session match.
     */
    static boolean isValid(boolean granted, double grantSession, double sessionId, long lastMsgToDevMs, long nowMs) {
        if (!granted) return false;
        if (sessionId != 0.0 && grantSession == sessionId) return true; // same world session
        return nowMs - lastMsgToDevMs < CONSENT_WINDOW_MS;              // sliding 20-minute window
    }

    /**
     * A relayed Developer message arrived for the player whose Discord thread it was posted in.
     * Called from the inbound Discord seam on a network thread; hops to the server thread and
     * delivers the message to that one player — or gates it behind the consent prompt. It is never
     * shown to any other player.
     *
     * @param owner   the Minecraft UUID of the player whose thread the message is in. {@code null}
     *                when the message isn't anchored to a player's thread (e.g. a top-level channel
     *                post) — such a message is shown to nobody in-game.
     * @param content the message text.
     */
    public static void onDevMessage(UUID owner, String content) {
        if (owner == null || content == null || content.isBlank()) return;
        MinecraftServer server = DtPlatform.get().getCurrentServer();
        if (server == null) return; // at the main menu there is no in-game surface; menu chat has it
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) return; // the thread's owner isn't online here; the menu chat still carries it
            if (isValid(player.getUUID())) {
                player.sendSystemMessage(deliveredLine(content));
                AchievementEvents.notifyCreatorAnswered(player);
            } else {
                holdAndPrompt(player, content);
            }
        });
    }

    private static void holdAndPrompt(ServerPlayer player, String content) {
        Deque<String> queue = PENDING.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        boolean firstPending = queue.isEmpty();
        queue.addLast(content);
        if (firstPending) {
            player.sendSystemMessage(Component.literal(PROMPT_TEXT).withStyle(ChatFormatting.YELLOW));
            DevMessageReport.postConsentRequested(player);
        }
    }

    /** Whether {@code playerId} has a Developer message held awaiting consent. */
    public static boolean hasPending(UUID playerId) {
        Deque<String> queue = PENDING.get(playerId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * The player typed {@code @Dev} while a Developer message was held: grant consent, flush the
     * held messages into in-game chat, notify Discord, and push the new state to the client to
     * persist. Called from {@link MentionPresenceEvents}'s chat listener on the server thread.
     */
    public static void onConsentAccepted(ServerPlayer player) {
        Deque<String> queue = PENDING.remove(player.getUUID());
        if (queue == null || queue.isEmpty()) return; // nothing was awaiting consent — plain mention

        long now = System.currentTimeMillis();
        Mirror m = MIRRORS.computeIfAbsent(player.getUUID(), k -> new Mirror());
        m.granted = true;
        m.grantSession = sessionId;
        m.lastMsgToDevMs = now;

        for (String content : queue) {
            player.sendSystemMessage(deliveredLine(content));
            AchievementEvents.notifyCreatorAnswered(player);
        }
        DevMessageReport.postConsentAccepted(player);
        DtNetSender.get().sendToPlayer(player, new ConsentUpdatePacket(true, sessionId, (double) now));
    }

    /**
     * The player sent an in-game chat line: if consent is already granted, slide the 20-minute
     * window. Called on every chat line from {@link MentionPresenceEvents}. The client slides its
     * own persisted copy independently from the same chat action, so no packet is needed here.
     */
    public static void onPlayerChatted(ServerPlayer player) {
        Mirror m = MIRRORS.get(player.getUUID());
        if (m != null && m.granted) {
            m.lastMsgToDevMs = System.currentTimeMillis();
        }
    }

    private static Component deliveredLine(String content) {
        return Component.literal("[Developer] ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(content).withStyle(ChatFormatting.WHITE));
    }
}
