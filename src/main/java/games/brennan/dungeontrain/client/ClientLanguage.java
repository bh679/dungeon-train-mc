package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.client.Minecraft;

/**
 * The client's currently selected language, and the one question DT keeps asking of it: is this a
 * Chinese-language client?
 *
 * <p>Chinese clients are the one audience for whom several of DT's outbound links are dead ends —
 * Discord, Patreon and Revolut are all walled in mainland China — so more than one surface needs the
 * same predicate: {@link games.brennan.dungeontrain.client.support.PaymentLinks} to pick the payment
 * route, and {@code TitleScreenCreditsButton} to show the Bilibili channel above the Discord
 * logomark. Both read this rather than each rolling their own.</p>
 *
 * <p><b>Language, not location</b> — Minecraft exposes no country. A Chinese-language client
 * anywhere counts, and a mainland player running an English client does not. It is the best signal
 * available and errs toward offering a working option to the people most likely to need one.</p>
 *
 * <p>The gate is the whole {@code zh} family via {@link LanguageFamily} — {@code zh_cn},
 * {@code zh_tw}, {@code zh_hk} and Classical Chinese {@code lzh}. Deliberately wide: it decides what
 * to <i>offer</i>, and offering a Traditional-Chinese reader a link they could also have reached
 * another way costs nothing. Anything that decides what to <b>take away</b> needs the narrower,
 * region-sensitive test instead — see {@code PaymentLinks.isSimplifiedChineseLocale}.</p>
 */
public final class ClientLanguage {

    private ClientLanguage() {}

    /** The raw selected locale (e.g. {@code "zh_cn"}), or null before the client is up. */
    public static String selected() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getLanguageManager() == null ? null : mc.getLanguageManager().getSelected();
    }

    /** Whether this client is running in any Chinese locale. */
    public static boolean isChinese() {
        return isChinese(selected());
    }

    /** Pure form — whether a raw locale belongs to the Chinese language family. */
    public static boolean isChinese(String locale) {
        return locale != null && "zh".equals(LanguageFamily.of(locale));
    }
}
