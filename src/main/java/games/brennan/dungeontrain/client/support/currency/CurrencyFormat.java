package games.brennan.dungeontrain.client.support.currency;

import net.minecraft.network.chat.Component;

/**
 * Gift amounts as shown on the Contribute screens. In AUD this is exactly the lang key's own text
 * ("$30"), so an AUD player sees what they always saw. In any other currency it is the AUD amount
 * converted at today's rate and rounded to whole units, behind a "~" ("~$20", "~€18"), with
 * {@link #note()} under it saying the gift itself is made in Australian dollars.
 */
public final class CurrencyFormat {

    private CurrencyFormat() {}

    /** True when amounts are being shown converted (anything but AUD, with a rate). */
    public static boolean converted() {
        return CurrencyChoice.current() != DisplayCurrency.AUD;
    }

    /** {@code aud} whole Australian dollars, shown in the current currency; {@code audKey} when AUD. */
    public static Component amount(int aud, String audKey) {
        DisplayCurrency c = CurrencyChoice.current();
        if (c == DisplayCurrency.AUD) return Component.translatable(audKey);
        return Component.literal(approx(aud, c, CurrencyRates.rate(c)));
    }

    /** Pure: "~" + the converted whole amount with its symbol. */
    static String approx(int aud, DisplayCurrency c, double rate) {
        return "~" + c.format(Math.round(aud * rate));
    }

    /**
     * "US dollars, approximate. Gifts are made in Australian dollars." while converted; the Contribute
     * panel's own "Amounts in Australian dollars." line otherwise.
     */
    public static Component note() {
        DisplayCurrency c = CurrencyChoice.current();
        if (c == DisplayCurrency.AUD) return Component.translatable("gui.dungeontrain.contribute.aud_note");
        return Component.translatable("gui.dungeontrain.currency.approx", Component.translatable(c.nameKey()));
    }
}
