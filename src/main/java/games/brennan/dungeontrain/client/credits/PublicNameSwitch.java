package games.brennan.dungeontrain.client.credits;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.social.WebsiteNameRelay;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Show my name: one switch for the player's name on Credits and on the website's player reviews.
 *
 * <p>The stored value is the Credits opt-out the Edit dialog already kept ({@link CreditsSelfEdits}
 * {@code hidden}), so a player who removed their Credits name before this switch existed starts with it
 * off, and the Options row and the Credits dialog can never disagree. Kid mode always reads off.</p>
 *
 * <p>Flipping from Options also asks the relay to remove or restore the player's Credits lines. A player
 * with no Credits line gets {@code not_yours} back, which is expected and ignored; any other refusal puts
 * the switch back (see {@link #setFromOptions}).</p>
 *
 * <p>Kid mode sends the reviews switch off but leaves the Credits lines alone.</p>
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

    /**
     * The Options row: store the choice, tell the reviews switch, and remove or restore the Credits lines.
     *
     * <p>The row shows the new value at once. If the relay refuses the Credits edit for any reason but
     * {@code not_yours} (no Credits line, which is expected), the choice is put back, the reviews switch
     * re-sent, a toast says so and {@code onReverted} runs on the render thread so the row can redraw.
     * Without the internet connection consent nothing can be saved, so that reverts too.</p>
     */
    public static void setFromOptions(boolean show, Runnable onReverted) {
        if (storedShown() == show) return;
        CreditsSelfEdits.setHidden(!show);
        WebsiteNameRelay.syncNow();
        (show ? CreditEditClient.restore() : CreditEditClient.remove()).whenComplete((result, err) -> {
            if (keeps(err == null ? result : null)) return;
            LOGGER.info("[DungeonTrain] Show my name: credits edit refused ({}), putting the switch back",
                err != null ? err.toString() : result == null ? "no answer" : result.error());
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                CreditsSelfEdits.setHidden(show);
                WebsiteNameRelay.syncNow();
                SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("gui.dungeontrain.options.show_name"),
                    Component.translatable("gui.dungeontrain.options.account.toggle_failed"));
                onReverted.run();
            });
        });
    }

    /** Whether a Credits edit's answer lets the Options choice stand, pure: ok, or no Credits line to edit. */
    static boolean keeps(CreditEditClient.Result result) {
        return result != null && (result.ok() || result.error() == CreditEditClient.Error.NOT_YOURS);
    }

    /** The Credits dialog's Hide/Show my name went through: store it and tell the reviews switch. */
    public static void onCreditsEdited(boolean hidden) {
        CreditsSelfEdits.setHidden(hidden);
        WebsiteNameRelay.syncNow();
    }
}
