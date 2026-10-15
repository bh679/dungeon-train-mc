package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.editor.EditorWorldLayout;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.net.DifficultyConfirmResponsePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.world.Difficulty;

/**
 * Client side of a difficulty change. The server holds the change and asks ({@code
 * DifficultyChangeConfirmEvents}); this opens the prompt over whatever screen the player was on
 * (normally the Options screen), remembering it so cancelling puts them back where they were — and
 * carries out the player's answer.
 *
 * <p>In a singleplayer run the prompt offers two ways forward, because a run <em>is</em> a world:</p>
 * <ul>
 *   <li><b>Save for next run</b> — {@link #saveForNextRun}: remember the difficulty locally
 *       ({@link ClientDisplayConfig#setNewRunDifficulty}); this run carries on unchanged and the next
 *       world starts on it.</li>
 *   <li><b>Abandon run &amp; restart now</b> — {@link #restartNow}: remember it, end the run the way
 *       the pause menu's Abandon does, and reboard straight into a fresh world on it.</li>
 * </ul>
 * <p>Both decline the held change on the server: the current world's difficulty never moves, so the
 * dying run's stats, advancements and gear stay on the difficulty it was played on. Where no world can
 * be created (a remote server) or a run doesn't apply (the Train Editor), {@link #applyNow} commits the
 * change in place as before — and remembers it too.</p>
 *
 * <p>No opt-out: unlike the Free Play prompt this one is rare, and gets the player's run wrong if they
 * misclick.</p>
 */
public final class DifficultyChangeConfirmClient {

    private DifficultyChangeConfirmClient() {}

    public static void onShow(Difficulty from, Difficulty to, boolean targetEmpty, boolean isolated) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        // Cycling the Difficulty button twice sends two changes: keep the screen the player actually
        // came from rather than stacking prompts on top of each other.
        Screen parent = current instanceof DifficultyChangeConfirmScreen confirm ? confirm.parent() : current;
        mc.setScreen(new DifficultyChangeConfirmScreen(parent, from, to, targetEmpty, isolated,
                isRunWorld(), mc.hasSingleplayerServer()));
    }

    /**
     * True in a singleplayer Dungeon Train run — the one place "abandon and restart now" means
     * something: the client can create the next world, and the world is a run (not the Train Editor).
     */
    public static boolean isRunWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer() && mc.level != null && !EditorWorldLayout.isEditorWorld(mc.level);
    }

    /**
     * The Options &gt; Dungeon Train &gt; General Difficulty row changed. Out of a world, or on a remote
     * server, that is only the remembered new-run difficulty. In a singleplayer world on another
     * difficulty it asks the server to change, so the same prompt (with the server's knowledge of the
     * loadout locker) decides what happens to this run; the row only moves once the player answers.
     */
    public static void onOptionsRowChanged(Difficulty to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.level != null && mc.getConnection() != null
                && mc.level.getDifficulty() != to && !mc.level.getLevelData().isHardcore()) {
            mc.getConnection().send(new ServerboundChangeDifficultyPacket(to));
            return;
        }
        ClientDisplayConfig.setNewRunDifficulty(to);
    }

    /** "Save for next run": nothing changes now; the next world starts on {@code to}. */
    static void saveForNextRun(Difficulty to) {
        DungeonTrainNet.sendToServer(new DifficultyConfirmResponsePacket(false));
        ClientDisplayConfig.setNewRunDifficulty(to);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(
                    "chat.dungeontrain.difficulty_next_run", to.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /**
     * "Abandon run &amp; restart now": the held change is declined (this run ends on the difficulty it
     * was played on), then the run is abandoned and the death reboards into a fresh world on
     * {@code to} — see {@link InstantRespawnReboard#expectRestartRun()}.
     */
    static void restartNow(Difficulty to) {
        DungeonTrainNet.sendToServer(new DifficultyConfirmResponsePacket(false));
        ClientDisplayConfig.setNewRunDifficulty(to);
        InstantRespawnReboard.expectRestartRun();
        // Close every screen first — in singleplayer that unpauses the integrated server so it can
        // process the kill (same order as PauseMenuLayoutHandler's abandon).
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new AbandonRunPacket());
    }

    /** "Change difficulty": commit the held change in this world (multiplayer, Train Editor). */
    static void applyNow(Difficulty to) {
        DungeonTrainNet.sendToServer(new DifficultyConfirmResponsePacket(true));
        ClientDisplayConfig.setNewRunDifficulty(to);
    }

    /** Backed out: the held change is dropped and nothing is remembered. */
    static void cancel() {
        DungeonTrainNet.sendToServer(new DifficultyConfirmResponsePacket(false));
    }
}
