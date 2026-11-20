package games.brennan.dungeontrain.client.credits;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.social.WebsiteNameRelay;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import org.slf4j.Logger;

/**
 * Show my name: one switch for the player's name on Credits and on the website's player reviews.
 *
 * <p>The stored value is the Credits opt-out the Edit dialog already kept ({@link CreditsSelfEdits}
 * {@code hidden}), so a player who removed their Credits name before this switch existed starts with it
 * off, and the Options row and the Credits dialog can never disagree. Kid mode always reads off.</p>
 *
 * <p>Flipping from Options also asks the relay to remove or restore the player's Credits lines. A player
 * with no Credits line gets {@code not_yours} back, which is expected and ignored.</p>
 */
public final class PublicNameSwitch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PublicNameSwitch() {}

    /** Whether the player's name may show. Off in Kid mode whatever the stored value says. */
    public static boolean isShown() {
        return isShown(ClientDisplayConfig.isKidMode(), CreditsSelfEdits.get().hidden());
    }

    /** The rule, pure. */
    static boolean isShown(boolean kidMode, boolean hidden) {
        return !kidMode && !hidden;
    }

    /** The stored choice alone, ignoring Kid mode (what the Options row shows when not inert). */
    public static boolean storedShown() {
        return !CreditsSelfEdits.get().hidden();
    }

    /** The Options row: store the choice, tell the reviews switch, and remove or restore the Credits lines. */
    public static void setFromOptions(boolean show) {
        if (storedShown() == show) return;
        CreditsSelfEdits.setHidden(!show);
        WebsiteNameRelay.syncNow();
        (show ? CreditEditClient.restore() : CreditEditClient.remove()).whenComplete((result, err) -> {
            if (err != null) {
                LOGGER.debug("[DungeonTrain] Show my name: credits edit failed: {}", err.toString());
            } else if (result != null && !result.ok() && result.error() != CreditEditClient.Error.NOT_YOURS) {
                LOGGER.debug("[DungeonTrain] Show my name: credits edit refused: {}", result.error());
            }
        });
    }

    /** The Credits dialog's Hide/Show my name went through: store it and tell the reviews switch. */
    public static void onCreditsEdited(boolean hidden) {
        CreditsSelfEdits.setHidden(hidden);
        WebsiteNameRelay.syncNow();
    }
}
