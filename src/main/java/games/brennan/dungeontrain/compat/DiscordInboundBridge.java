package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.compat.InboundDiscordHooks;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.event.DevMessageConsent;

/**
 * Bridges DiscordPresence's inbound-message seam ({@link InboundDiscordHooks}) to two consumers when
 * a relayed Discord message is authored by the dev:
 * <ul>
 *   <li>the remote-echo chat privacy guard — stamps {@code RemoteEchoEncounters} so a player who
 *       chats near an echo within the next five minutes has their words withheld from the public
 *       echo story;</li>
 *   <li>the in-game dev-message consent flow ({@link DevMessageConsent#onDevMessage}) — shows the
 *       message in in-game chat, gated behind a consent prompt.</li>
 * </ul>
 *
 * <p>Mirrors {@link PlayerMobSpawnBridge}: the hard reference to {@code InboundDiscordHooks} lives
 * only inside {@link #install()}, so this class loads even when the seam is absent; the caller gates
 * on {@code ModList.isLoaded} and catches {@link Throwable}, so a DiscordPresence build predating the
 * seam (≤ 0.40.0) degrades to "@-mention guard only" rather than a crash.</p>
 */
public final class DiscordInboundBridge {

    private DiscordInboundBridge() {}

    /** Subscribe the dev-contact guard and the dev-message consent flow to DP's inbound-message seam. */
    public static void install() {
        InboundDiscordHooks.install((authorId, authorName, content) -> {
            if (DungeonTrain.BRENNAN_DISCORD_ID.equals(authorId)) {
                RemoteEchoEncounters.markDevContact();
                DevMessageConsent.onDevMessage(content);
            }
        });
    }
}
