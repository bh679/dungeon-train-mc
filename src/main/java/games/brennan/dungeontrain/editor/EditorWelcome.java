package games.brennan.dungeontrain.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a friendly onboarding message in chat 2.2 seconds after a player first
 * enters the Dungeon Train editor each play session.
 *
 * <p>"Entered the editor" reuses the three command paths that already fire the
 * {@code EDITOR_ACTION "entered_editor"} advancement trigger (category, carriage,
 * and tunnel enters in {@code EditorCommand}); they all route through
 * {@code EditorCommand.markEnteredEditor}, which calls {@link #showOnEnter}.</p>
 *
 * <p>The 2.2s delay is scheduled like {@code CinematicIntroService}: an entry is
 * parked with an overworld game-tick deadline and sent by {@link #tick}, which is
 * pumped once per server tick from {@code PlayerJoinEvents.onLevelTick}.
 * "Once per play session" is gated by an in-memory UUID set cleared on logout
 * (via {@link #forget}), so a relog re-shows the welcome.</p>
 */
public final class EditorWelcome {

    private EditorWelcome() {}

    /** Delay before the welcome lands: 2.2 seconds at 20 ticks/second = 44 ticks. */
    private static final long WELCOME_DELAY_TICKS = 44L;

    /** Players already welcomed (or currently scheduled) this session; gates "once per session". */
    private static final Set<UUID> WELCOMED = ConcurrentHashMap.newKeySet();

    /** Player UUID → overworld game-tick deadline at which the scheduled welcome is sent. */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

    /**
     * The first time {@code player} enters the editor this session, schedule the
     * welcome to land {@link #WELCOME_DELAY_TICKS} ticks (≈2.2s) later; a no-op on
     * every later entry until they log out and back in.
     */
    public static void showOnEnter(ServerPlayer player) {
        if (!WELCOMED.add(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        long deadline = server.overworld().getGameTime() + WELCOME_DELAY_TICKS;
        PENDING.put(player.getUUID(), deadline);
    }

    /**
     * Per-tick pump (hook from the overworld {@code LevelTickEvent.Post}, next to
     * {@code CinematicIntroService.tick}). Sends each scheduled welcome once its
     * 2.2s delay has elapsed.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long now = server.overworld().getGameTime();
        PENDING.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) p.sendSystemMessage(buildWelcome());
            return true;
        });
    }

    /** Drop tracking on logout (do not touch the now-offline entity). */
    public static void forget(UUID playerId) {
        WELCOMED.remove(playerId);
        PENDING.remove(playerId);
    }

    /**
     * The multi-line welcome, sent as a single chat entry. "@brennanhatton" is a
     * clickable mention that pre-fills the chat box (relayed to Brennan on Discord
     * by the bundled Discord Presence mod) — styled like the clickable link in
     * {@code ExportCommand.formatSuccess}.
     */
    private static Component buildWelcome() {
        Component mention = Component.literal("@brennanhatton")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "@brennanhatton "))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Click to message Brennan — your message reaches him on Discord"))));

        return Component.empty()
            .append(Component.literal("Welcome to the Dungeon Train Editor!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("\nThis is a full editor for everything in the train."))
            .append(Component.literal("\nFrom train carriage templates to custom loot tables editor."))
            .append(Component.literal("\nIf you have any questions - message Brennan in here with "))
            .append(mention)
            .append(Component.literal(
                "\nHe will be very enthusiastic that you're using the editor and do what he can to support you!"));
    }
}
