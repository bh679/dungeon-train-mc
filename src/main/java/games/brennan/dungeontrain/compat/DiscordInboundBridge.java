package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.compat.DiscordCommandHooks;
import games.brennan.discordpresence.compat.InboundDiscordHooks;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.event.DevMessageConsent;

/**
 * Bridges DiscordPresence's inbound-message seams to two consumers when a relayed Discord message is
 * authored by the dev:
 * <ul>
 *   <li>the remote-echo chat privacy guard — stamps {@code RemoteEchoEncounters} so a player who
 *       chats near an echo within the next five minutes has their words withheld from the public
 *       echo story;</li>
 *   <li>the in-game dev-message consent flow ({@link DevMessageConsent#onDevMessage}) — shows the
 *       message in in-game chat, gated behind a consent prompt.</li>
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
        DiscordCommandHooks.install((authorId, authorName, content, reply) -> {
            if (!DungeonTrain.BRENNAN_DISCORD_ID.equals(authorId)) {
                return false;
            }
            DevMessageConsent.onDevMessage(content);
            return true; // suppress DiscordPresence's own relay — the consent flow now owns delivery
        });
    }
}
