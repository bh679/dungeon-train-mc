package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The "Last Active" card on the death screen's donation page: how long ago work last landed on the
 * project — the newest commit across Dungeon Train, its sibling mods and the relay.
 *
 * <pre>
 *   3 hours
 *   Last Active
 * </pre>
 *
 * <p><b>Why this and not the updates card.</b> {@link UpdateStats} counts releases, and the
 * auto-release cascade publishes about twenty of them on a timer for a fortnight after every real
 * one. A run of updates therefore cannot answer the question a would-be supporter is actually
 * asking — is anyone still working on this? — because the cascade would keep answering "yes" long
 * after everyone went home. A commit timestamp answers it honestly.</p>
 *
 * <p><b>Relay-served, never baked.</b> Unlike {@link DevHours}, whose cumulative total is fixed at
 * build time and stays true, a recency figure baked into a jar starts lying the moment it ships: a
 * jar released in March would still be claiming March in September, understating the project's
 * activity by months. The figure comes off the relay's {@code activity} block (dp-relay
 * {@code activity.js}) or not at all.</p>
 *
 * <p><b>Unknown is withheld; old is shown.</b> No relay block, no timestamp, or a timestamp in the
 * future (clock skew, which would render as a negative duration) means the card is not drawn — the
 * same rule {@link UpdateStats#hasCount} follows. But a figure that is merely OLD is drawn as it
 * is. Hiding the card once the number stops flattering the project would make it a badge that
 * appears only when it argues for donating, and a page that picks which true things a donor is
 * allowed to see is not one worth putting a payment button on.</p>
 */
public final class LastActive {

    private LastActive() {}

    /** Whether there is a figure worth drawing — see the class note on unknown versus old. */
    public static boolean known(long lastCommitAtMs, Instant now) {
        return lastCommitAtMs > 0L && lastCommitAtMs <= now.toEpochMilli();
    }

    /** The card's figure — "3 hours" — for the language chosen in Minecraft. */
    public static Component value(long lastCommitAtMs, Instant now) {
        return value(lastCommitAtMs, now, ClientLanguage.selected());
    }

    /**
     * As {@link #value(long, Instant)}, for an explicit Minecraft language code — kept pure for
     * tests.
     *
     * <p>Formatting is {@link PresenceLine#agoComponent}, the same coarse largest-whole-unit
     * phrasing the presence lines and the updates tooltip already use, so the mod never grows a
     * second opinion about how to say "three hours" in twenty languages.</p>
     */
    public static Component value(long lastCommitAtMs, Instant now, String localeCode) {
        Duration elapsed = Duration.between(Instant.ofEpochMilli(lastCommitAtMs), now);
        return PresenceLine.agoComponent(localeCode, elapsed);
    }

    /** The card's caption. */
    public static Component label() {
        return Component.translatable("gui.dungeontrain.death.narr.lbl_last_active");
    }

    /** The hover tooltip, naming the same figure in a sentence. */
    public static Component tooltip(long lastCommitAtMs, Instant now) {
        return tooltip(lastCommitAtMs, now, ClientLanguage.selected());
    }

    /** As {@link #tooltip(long, Instant)}, for an explicit Minecraft language code. */
    public static Component tooltip(long lastCommitAtMs, Instant now, String localeCode) {
        return Component.translatable("gui.dungeontrain.death.narr.tip_last_active",
                value(lastCommitAtMs, now, localeCode));
    }
}
