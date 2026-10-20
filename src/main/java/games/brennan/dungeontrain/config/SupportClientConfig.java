package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.client.support.currency.DisplayCurrency;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side Contribute settings ({@code config/dungeontrain-support-client.toml}). Its own file,
 * like the Live Feed knobs, so the display config record stays untouched; nothing here has any
 * fair-play meaning.
 */
public final class SupportClientConfig {

    public static final ModConfigSpec SPEC;
    /** The currency gift amounts are shown in; empty = never picked (the game language decides). */
    public static final ModConfigSpec.ConfigValue<String> CURRENCY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("contribute");
        CURRENCY = b
            .comment("The currency gift amounts are shown in on the Contribute screens (AUD, USD, EUR, GBP, CAD, NZD, CNY, JPY, KRW, BRL, MXN, PLN, INR). Display only: gifts are always made in Australian dollars. Empty = follow the game language.")
            .define("currency", "", v -> v instanceof String s && (s.isEmpty() || DisplayCurrency.byCode(s) != null));
        b.pop();
        SPEC = b.build();
    }

    private SupportClientConfig() {}

    /** The picked currency, or {@code null} when the player never picked one (or before load). */
    public static DisplayCurrency currency() {
        return SPEC.isLoaded() ? DisplayCurrency.byCode(CURRENCY.get()) : null;
    }

    public static void setCurrency(DisplayCurrency c) {
        if (!SPEC.isLoaded()) return;
        CURRENCY.set(c == null ? "" : c.code());
        CURRENCY.save();
    }
}
