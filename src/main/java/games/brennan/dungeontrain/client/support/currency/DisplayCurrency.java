package games.brennan.dungeontrain.client.support.currency;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * The currencies a player may view gift amounts in. Display only: every gift is charged in AUD, and
 * a converted figure always comes with the "approximate. Gifts are made in Australian dollars" note.
 *
 * <p>Must match the relay's {@code fx.js DISPLAY_CURRENCIES} (plus AUD), which fetches one rate per
 * code; a code the relay sends no rate for is simply not offered. Each has a 12x8 flag sprite at
 * {@code textures/gui/sprites/flag/<code>.png}, drawn by {@code scripts/currency/make-flags.py}
 * (which also writes the website's copies). Flags, not emoji: Minecraft's font has no flag glyphs.</p>
 */
public enum DisplayCurrency {
    AUD("$", false),
    USD("$", false),
    EUR("€", false),
    GBP("£", false),
    CAD("$", false),
    NZD("$", false),
    CNY("¥", false),
    JPY("¥", false),
    KRW("₩", false),
    BRL("R$", false),
    MXN("$", false),
    PLN(" zł", true),
    INR("₹", false);

    private final String symbol;
    private final boolean suffix;

    DisplayCurrency(String symbol, boolean suffix) {
        this.symbol = symbol;
        this.suffix = suffix;
    }

    /** The ISO 4217 code, as shown on the button and sent to the relay ("EUR"). */
    public String code() {
        return name();
    }

    /** The flag sprite in the GUI atlas. */
    public ResourceLocation flag() {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", "flag/" + name().toLowerCase(Locale.ROOT));
    }

    /** The currency's name for the "approximate" note ("US dollars"). */
    public String nameKey() {
        return "gui.dungeontrain.currency.name." + name().toLowerCase(Locale.ROOT);
    }

    /** A whole amount with its symbol, before or after: "€9", "R$12", "9 zł", "₩144,000". */
    public String format(long whole) {
        String n = String.format(Locale.ROOT, "%,d", whole);
        return suffix ? n + symbol : symbol + n;
    }

    /** The currency for an ISO code (any case), or {@code null} for one this jar doesn't offer. */
    public static DisplayCurrency byCode(String code) {
        if (code == null || code.isBlank()) return null;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
