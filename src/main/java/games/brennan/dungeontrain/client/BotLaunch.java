package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.version.UpdatePromptScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Lets a client launched by a script or agent get past the two title-screen cards nobody is there to
 * answer: Discord Presence's internet-consent card and DT's update card. With the switch on, neither
 * card ever opens; the title screen just stays.
 *
 * <p>Invisible to players by design: no UI, no lang key, no command, no chat line, and inert in release
 * jars ({@link DungeonTrain#isDevBuild()}: any branch but main). It is set only at launch, by {@code -Ddungeontrain.bot=<mode>} or env {@code DUNGEONTRAIN_BOT=<mode>}
 * ({@code ./gradlew runClient -Pbot}, on by default from an agent shell; see build.gradle):</p>
 * <ul>
 *   <li>{@code deny} (or {@code 1}/{@code true}/{@code yes}): skip the cards, record nothing. Consent
 *       stays unanswered or denied, so the client makes no network use.</li>
 *   <li>{@code grant}: skip the cards and grant consent, exactly the writes the card's button makes
 *       (the content-mode question keeps its Adult default). Persists in that run dir's config.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BotLaunch {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String PROPERTY = "dungeontrain.bot";
    static final String ENV = "DUNGEONTRAIN_BOT";

    enum Mode { OFF, DENY, GRANT }

    /** Dev builds only: a release jar (built from main) ignores the switch, so players can't use it. */
    private static final Mode MODE = DungeonTrain.isDevBuild()
            ? parse(System.getProperty(PROPERTY, System.getenv(ENV)))
            : Mode.OFF;

    private static boolean consentLogged;
    private static boolean updateLogged;

    private BotLaunch() {}

    /** Reads a launch value; anything unrecognised (or unset) is {@link Mode#OFF}. */
    static Mode parse(String raw) {
        if (raw == null) return Mode.OFF;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "deny", "1", "true", "yes" -> Mode.DENY;
            case "grant" -> Mode.GRANT;
            default -> Mode.OFF;
        };
    }

    /** True when this client was launched as a bot. */
    public static boolean active() {
        return MODE != Mode.OFF;
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (MODE == Mode.OFF) return;
        Screen next = event.getNewScreen();
        if (next instanceof UpdatePromptScreen) {
            event.setCanceled(true);
            if (!updateLogged) {
                updateLogged = true;
                LOGGER.info("[DungeonTrain] bot launch ({}): update card skipped", PROPERTY);
            }
        } else if (next instanceof NetworkConsentScreen) {
            if (MODE == Mode.GRANT) grantConsent();
            event.setCanceled(true);
            if (!consentLogged) {
                consentLogged = true;
                LOGGER.info("[DungeonTrain] bot launch ({}={}): internet consent card skipped",
                        PROPERTY, MODE.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void grantConsent() {
        try {
            DiscordPresenceClientConfig.setConsentVersion(DiscordCredentials.providerNetworkConsentVersion());
            DiscordPresenceClientConfig.setConsent(DiscordPresenceClientConfig.Consent.GRANTED);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] bot launch: could not grant internet consent", e);
        }
    }
}
