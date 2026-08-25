package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.world.CustomContentChoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

/**
 * Asks the custom-content question at the moment a run starts — pressing <b>New World</b> on the
 * title screen, or <b>reboard</b> after a death — rather than after the world is already up.
 *
 * <p>The question is a trade: play with your own train designs and the run is Free Play, so it
 * won't add to your profile, or leave them off for this world and keep your stats. Asked at join
 * it was a trade already made — the world had generated from that content and the Free Play badge
 * was on before the player was offered the choice. Asked here it is a real one, and the "no"
 * answer means a world that never loads the content at all.</p>
 *
 * <p>Covers the two entry points a player actually starts a run through, both of which funnel
 * through one method each: {@code DevQuickWorldHandler.openLevel} (the title screen's New World
 * button, which DT shows in place of vanilla's Singleplayer) and
 * {@code DeathScreenLayoutHandler.launchWorld} (the death screen's reboard, the immediate-respawn
 * reboard, and {@code /new-world}). The secondary route — the vanilla world list's own Create New
 * World — still reaches the world unanswered and gets the join-time prompt
 * ({@code CustomContentPromptEvents}), which remains in place for it and for every multiplayer
 * join, where the client cannot decide for a world it does not own.</p>
 */
public final class CustomContentGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CustomContentGate() {}

    /**
     * Put the question before {@code launch}, if there is a question to put.
     *
     * <p>Callers use it as a guard clause: {@code if (askFirst(screen, this::go)) return; go();} —
     * either the prompt takes over and runs {@code launch} once answered, or there was nothing to
     * ask and the caller proceeds untouched.</p>
     *
     * @param parent screen to return to if the player backs out of starting a world at all
     * @param launch what to run once the answer is recorded
     * @return true when a prompt was opened and {@code launch} now belongs to it; false when the
     *         caller should proceed immediately
     */
    public static boolean askFirst(Screen parent, Runnable launch) {
        boolean hasContent;
        try {
            hasContent = EditorContentIntegrity.hasCustomContent();
        } catch (RuntimeException e) {
            // Probing walks player-editable folders from the title screen, outside the server
            // lifecycle this scan normally runs in. Failing to answer the question must never cost
            // the player the ability to start a world — fall through, and the world gets the
            // join-time prompt instead, which is exactly the fallback that already exists.
            LOGGER.warn("[DungeonTrain] Couldn't check for custom content before starting a world; "
                + "deferring the question to join time.", e);
            PendingCustomContentChoice.clear();
            return false;
        }
        if (!hasContent) {
            // Nothing authored or imported — the question doesn't arise, and any answer left over
            // from a previous ask would be about content that is no longer there.
            PendingCustomContentChoice.clear();
            return false;
        }

        CustomContentPreference remembered = ClientDisplayConfig.getCustomContentPreference();
        if (!remembered.asks()) {
            // "Remember decision" was ticked on an earlier world, or the preference was set in
            // Options → Dungeon Train. Answer silently and let the world start uninterrupted.
            CustomContentChoice choice = remembered.keepsContent()
                ? CustomContentChoice.ALLOW
                : CustomContentChoice.DISABLE;
            LOGGER.info("[DungeonTrain] Custom content answered from the remembered preference "
                + "before the world starts: {}", choice);
            PendingCustomContentChoice.set(choice);
            return false;
        }

        String packages = String.join(", ", EditorContentIntegrity.contentPackageNames());
        LOGGER.info("[DungeonTrain] Asking about custom content ({}) before starting a world.", packages);
        Minecraft.getInstance().setScreen(new CustomContentPromptScreen(packages, parent, keepContent -> {
            CustomContentChoice choice = keepContent
                ? CustomContentChoice.ALLOW
                : CustomContentChoice.DISABLE;
            LOGGER.info("[DungeonTrain] Custom content answered before the world starts: {}", choice);
            PendingCustomContentChoice.set(choice);
            launch.run();
        }));
        return true;
    }
}
