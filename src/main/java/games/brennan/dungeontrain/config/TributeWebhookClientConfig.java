package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.discord.TributeWebhook;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The player's Tribute webhook ({@code config/dungeontrain-tribute-webhook-client.toml}): a Discord
 * webhook link that every photo they pay Tribute to is also posted to. Its own small file, like the
 * other client configs, so the display config record stays untouched. The link is a secret: it stays
 * on this computer and goes only to the server the player is on (memory only there).
 */
public final class TributeWebhookClientConfig {

    public static final ModConfigSpec SPEC;
    /** The webhook link, or empty for none. */
    public static final ModConfigSpec.ConfigValue<String> URL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("tributeWebhook");
        URL = b
            .comment("A Discord webhook link (https://discord.com/api/webhooks/...). Every photo you pay Tribute to",
                     "is also posted there. Empty = none. Set it in Options > Account. Keep it private: anyone with",
                     "the link can post to that channel.")
            .define("url", "", v -> v instanceof String s && (s.isEmpty() || TributeWebhook.isValid(s)));
        b.pop();
        SPEC = b.build();
    }

    private TributeWebhookClientConfig() {}

    /** The saved link, or {@code ""} when none is set (or before load). */
    public static String url() {
        return SPEC.isLoaded() ? TributeWebhook.normalize(URL.get()) : "";
    }

    public static boolean isSet() {
        return TributeWebhook.isValid(url());
    }

    /** Save {@code url}, or clear it with a blank. An invalid link is not saved; returns whether it was. */
    public static boolean setUrl(String url) {
        if (!SPEC.isLoaded()) return false;
        String clean = TributeWebhook.normalize(url);
        if (!clean.isEmpty() && !TributeWebhook.isValid(clean)) return false;
        URL.set(clean);
        URL.save();
        return true;
    }
}
