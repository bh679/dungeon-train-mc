package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.support.DonateCards;
import org.jetbrains.annotations.Nullable;

/**
 * A dev-only override of the donation page's A/B arm, set by {@code /dt-ledger-preview arm <id>}.
 *
 * <p>Which arm a client draws is a hash of its own uuid, so it cannot be chosen — which makes four
 * of the five arms unreachable on any given machine and unreviewable before they ship. This holds a
 * forced arm for the current session so each can be opened and screenshotted.</p>
 *
 * <p><b>Never reported.</b> The forced arm changes what is DRAWN and not what is measured: the
 * telemetry keeps carrying the arm the player's uuid actually buckets into. A dev flipping through
 * layouts must not be able to write rows attributing their clicks to an arm they were never
 * assigned — that would put fabricated data into the experiment it exists to test.</p>
 *
 * <p>Registered only on dev builds (see {@code DonationLedgerPreviewCommand}), so a release jar has
 * no way to set it.</p>
 */
public final class DonateArmOverride {

    private static volatile @Nullable DonateCards.Arm forced;

    private DonateArmOverride() {}

    public static void set(@Nullable DonateCards.Arm arm) {
        forced = arm;
    }

    /** The forced arm, or null when the page should use the player's own assignment. */
    public static @Nullable DonateCards.Arm get() {
        return forced;
    }
}
