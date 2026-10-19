package games.brennan.dungeontrain.client.support.currency;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.credits.DonationPrefsClient;
import games.brennan.dungeontrain.config.SupportClientConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which currency the Contribute screens show amounts in, and where that choice is kept.
 *
 * <ul>
 *   <li><b>Local</b> ({@link SupportClientConfig}): works offline and before network consent.</li>
 *   <li><b>Per account on the relay</b> ({@code donation/prefs}, only with network consent): a pick
 *       is pushed there, and a fresh install with nothing saved locally adopts the relay's value the
 *       first time a Contribute screen opens.</li>
 *   <li><b>Neither</b>: the game language decides ({@link CurrencyDefaults}).</li>
 * </ul>
 *
 * <p>A choice with no rate to convert at (relay never reached, a currency the relay dropped) shows
 * as AUD, so a figure is never made up.</p>
 */
public final class CurrencyChoice {

    private static final AtomicBoolean RELAY_ASKED = new AtomicBoolean();

    private CurrencyChoice() {}

    /** The currency amounts show in right now: the saved pick, else the language default, else AUD. */
    public static DisplayCurrency current() {
        DisplayCurrency picked = SupportClientConfig.currency();
        DisplayCurrency wanted = picked != null ? picked : CurrencyDefaults.forLanguage(ClientLanguage.selected());
        return CurrencyRates.usable(wanted) ? wanted : DisplayCurrency.AUD;
    }

    /** The currencies the list offers: AUD and every one the relay sent a rate for, in enum order. */
    public static List<DisplayCurrency> available() {
        List<DisplayCurrency> out = new ArrayList<>();
        for (DisplayCurrency c : DisplayCurrency.values()) {
            if (CurrencyRates.usable(c)) out.add(c);
        }
        return out;
    }

    /** The player picked {@code c} on {@code surface}: save it, tell the relay (with consent), count it. */
    public static void pick(DisplayCurrency c, String surface) {
        SupportClientConfig.setCurrency(c);
        RELAY_ASKED.set(true); // a local pick now outranks whatever the relay held
        DonationPrefsClient.setCurrency(c.code());
        UiAnalytics.currencyPicked(surface, c.code());
    }

    /**
     * Once per launch, when nothing is saved locally and the player has given network consent, ask
     * the relay for the currency they picked on another install; adopt it and run {@code relayout}
     * on the client thread if the screen should redraw.
     */
    public static void adoptFromRelay(Runnable relayout) {
        if (SupportClientConfig.currency() != null || !DiscordPresenceClientConfig.isGranted()) return;
        if (!RELAY_ASKED.compareAndSet(false, true)) return;
        DonationPrefsClient.getCurrency().thenAccept(prefs -> {
            DisplayCurrency fromRelay = prefs.ok() ? DisplayCurrency.byCode(prefs.currency()) : null;
            if (fromRelay == null) return;
            Minecraft.getInstance().execute(() -> {
                if (SupportClientConfig.currency() != null) return; // picked locally meanwhile
                SupportClientConfig.setCurrency(fromRelay);
                relayout.run();
            });
        });
    }
}
