package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.compat.DiscordCommandHooks;
import games.brennan.discordpresence.compat.InboundDiscordHooks;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.event.DevMessageConsent;
import games.brennan.dungeontrain.logrequest.LogRequestManager;

import java.util.UUID;

/**
 * Bridges DiscordPresence's inbound-message seams to two consumers when a relayed Discord message is
 * authored by the dev:
 * <ul>
 *   <li>the remote-echo chat privacy guard — stamps {@code RemoteEchoEncounters} so a player who
 *       chats near an echo within the next five minutes has their words withheld from the public
 *       echo story;</li>
 *   <li>the in-game dev-message consent flow ({@link DevMessageConsent#onDevMessage}) — shows the
 *       message to the one player whose thread it was posted in, gated behind a consent prompt.</li>
 * </ul>
 *
 * <p>The consent flow registers via {@link DiscordCommandHooks}, not the plain observer seam
 * {@link InboundDiscordHooks}: DiscordPresence's own Discord→game relay is anchored-reply based (a
 * dev reply inside the player's own thread is always relayable) and on by default
 * ({@code relayDiscordToGame}), so it would otherwise deliver the raw text into the player's chat
 * independently of — and before — DT's consent decision, defeating the "never appears without
 * consent" guarantee. Returning {@code true} marks the message "handled", which tells
 * DiscordPresence to skip its own relay, leaving {@link DevMessageConsent#onDevMessage} as the sole
 * delivery path.</p>
 *
 * <p>Mirrors {@link PlayerMobSpawnBridge}: the hard references to the DiscordPresence seams live only
 * inside {@link #install()}, so this class loads even when they're absent; the caller gates on
 * {@code ModList.isLoaded} and catches {@link Throwable}, so a DiscordPresence build predating either
 * seam (InboundDiscordHooks: 0.41.0+, DiscordCommandHooks: 0.43.0+) degrades to whichever guard is
 * still available rather than crashing.</p>
 *
 * <p>Discord reactions in chat (DP 0.69.0+: {@link DiscordChatSeams}) — every line that reaches in-game chat
 * from or to a player's Discord thread keeps its Discord message id, so reactions added in Discord show
 * on it. On an older DP the original command seam is used and those lines simply carry no reactions.</p>
 */
public final class DiscordInboundBridge {

    private DiscordInboundBridge() {}

    /** Subscribe the dev-contact guard and the dev-message consent flow to DP's inbound-message seams. */
    public static void install() {
        InboundDiscordHooks.install((authorId, authorName, content) -> {
            if (DungeonTrain.BRENNAN_DISCORD_ID.equals(authorId)) {
                RemoteEchoEncounters.markDevContact();
            }
        });
        try {
            // DP 0.69.0+: the message id rides along, so the line can carry its Discord reactions.
            DiscordChatSeams.install();
        } catch (LinkageError older) {
            // An older DP (a CurseForge pack can ship one beside DT's nested copy): dev messages
            // still arrive through the original seam, without reactions.
            DiscordCommandHooks.install((authorId, authorName, content, targetPlayerUuid, reply) ->
                onCommand(authorId, content, targetPlayerUuid, null, reply));
        }
    }

    /**
     * A relayed Discord message, offered to DT before DP's own relay. Returns true when DT took it:
     * the dev's {@code /bug} and {@code /version}, and every other dev message (the consent flow owns
     * its delivery). {@code messageId} is null on a DP older than 0.69.0.
     */
    static boolean onCommand(String authorId, String content, String targetPlayerUuid, String messageId,
                             DiscordCommandHooks.Reply reply) {
        if (!DungeonTrain.BRENNAN_DISCORD_ID.equals(authorId)) {
            return false;
        }
        // "/bug" asks the thread's player for their logs instead of being shown as a message.
        if (LogRequestManager.isCommand(content)) {
            LogRequestManager.onThreadCommand(parseUuid(targetPlayerUuid), reply);
            return true;
        }
        // "/version" is the relay's private lookup, answered from its own records: never a message.
        if (LogRequestManager.isVersionCommand(content)) {
            return true;
        }
        // Scope to the one player whose thread this message is in (null when unanchored — the
        // consent flow then shows it to nobody in-game). Never a global broadcast.
        DevMessageConsent.onDevMessage(parseUuid(targetPlayerUuid), messageId, content, reply);
        return true; // suppress DiscordPresence's own relay — the consent flow now owns delivery
    }

    /** The Minecraft player UUID the inbound seam carries, or null when it's absent or malformed. */
    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
