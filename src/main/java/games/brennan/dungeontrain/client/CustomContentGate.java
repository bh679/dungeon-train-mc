package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.world.CustomContentChoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.GameType;
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
     * Answer without asking, for a run that starts with no one at the menu — the automatic reboard
     * after a death with immediate respawn on ({@code InstantRespawnReboard}). Takes the remembered
     * preference, else the last answer actually given, else declines.
     *
     * <p>Declining as the last resort is deliberate. Carrying a previous <em>ALLOW</em> forward is
     * the player's own consent, given once and reused. Inventing one they never gave would put a
     * run into Free Play that nobody agreed to, which is the one thing the ask-before-assign rule
     * exists to stop. Only reachable on a first-ever death with immediate respawn already on.</p>
     */
    public static void answerFromMemory() {
        try {
            if (!EditorContentIntegrity.hasCustomContent()) {
                PendingCustomContentChoice.clear();
                return;
            }
        } catch (RuntimeException e) {
            // Same guard askFirst makes, and it matters more here: this runs mid-reboard with the
            // old world tearing down, and the reboard has already been scheduled. Leaving the slot
            // clear lets the new world ask at join rather than failing the respawn outright.
            LOGGER.warn("[DungeonTrain] Couldn't check for custom content during an automatic "
                + "reboard; leaving the question to join time.", e);
            PendingCustomContentChoice.clear();
            return;
        }
        CustomContentPreference remembered = ClientDisplayConfig.getCustomContentPreference();
        CustomContentPreference source = remembered.asks()
            ? ClientDisplayConfig.getLastCustomContentAnswer()
            : remembered;
        CustomContentChoice choice = !source.asks() && source.keepsContent()
            ? CustomContentChoice.ALLOW
            : CustomContentChoice.DISABLE;
        LOGGER.info("[DungeonTrain] Automatic reboard — reusing the last custom content answer "
            + "without asking: {} (from {})", choice, source);
        PendingCustomContentChoice.set(choice);
    }

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
        return askFirst(GameType.SURVIVAL, parent, launch);
    }

    /**
     * As {@link #askFirst(Screen, Runnable)}, for a world whose starting game mode is known.
     *
     * <p>The question is only worth asking about a run that would otherwise <b>count</b>. A world
     * created in creative or spectator is Free Play by virtue of its own game mode, so there is no
     * trade to offer: that covers the Train Editor (whose world is creative by construction) and
     * the dev creative world, without either having to be recognised by name.</p>
     *
     * <p>Those worlds record {@code ALLOW} rather than declining. An editor world must load the
     * player's own designs — editing them is the point — and the run is Free Play either way, so
     * there is nothing gained by withholding them.</p>
     */
    public static boolean askFirst(GameType mode, Screen parent, Runnable launch) {
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            LOGGER.info("[DungeonTrain] New world starts in {} — Free Play regardless, so the "
                + "custom content question doesn't arise; keeping the content.", mode);
            PendingCustomContentChoice.set(CustomContentChoice.ALLOW);
            return false;
        }
        return askCounting(parent, launch);
    }

    private static boolean askCounting(Screen parent, Runnable launch) {
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
