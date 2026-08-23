package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.BugCommand;
import games.brennan.dungeontrain.command.DtpCommand;
import games.brennan.dungeontrain.command.EchoEncounterTestCommand;
import games.brennan.dungeontrain.command.FixAisConfigCommand;
import games.brennan.dungeontrain.command.FixConfigCommand;
import games.brennan.dungeontrain.command.ReportCarriageCommand;
import games.brennan.dungeontrain.command.TrainCommand;
import games.brennan.dungeontrain.editor.EditorEditRecorder;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;

/**
 * Forge event-bus subscriber: hooks command registration, and arms the editor's
 * undo history ahead of any editor command.
 * Registered automatically via {@link EventBusSubscriber}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CommandEvents {

    private CommandEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TrainCommand.register(event.getDispatcher(), event.getBuildContext());
        // /bug — opens the feedback survey jumped to the bug-report question (logs ship on a
        // real-bug answer, same as the death screen). Bundled DP is always present.
        BugCommand.register(event.getDispatcher());
        // /dtp <x> — teleport to world-X and guarantee a train is there to land on.
        DtpCommand.register(event.getDispatcher());
        // /fixaisconfig — restores AIS config defaults (the AIS-data Free Play fix action).
        FixAisConfigCommand.register(event.getDispatcher());
        games.brennan.dungeontrain.command.CustomContentCommand.register(event.getDispatcher());
        // /fixconfig — moves every governed config aside so defaults regenerate (the DT-config
        // Free Play fix action; the same reset the title-screen prompt offers).
        FixConfigCommand.register(event.getDispatcher());
        // /reportcarriage [reason] — player-facing report of the shared carriage underfoot (also a
        // subcommand of the op-gated /dungeontrain root). Any player, any game mode.
        ReportCarriageCommand.register(event.getDispatcher());
        // Dev-only: a relay-free way to drive the remote-echo encounter journal end-to-end.
        // Never registered in production, and only when PlayerMob (whose types the command
        // references) is present.
        if (!FMLEnvironment.production && ModList.get().isLoaded("playermob")) {
            EchoEncounterTestCommand.register(event.getDispatcher());
        }
    }

    /**
     * Arm an editor-config snapshot before any editor command runs, so menu
     * changes made by command — weights, gates, stage links, renames, contents
     * toggles — land in the undo history.
     *
     * <p>{@link CommandEvent} fires <b>before</b> execution, which is exactly
     * when the "before" reading has to be taken; the end-of-tick flush in
     * {@link EditorEditRecorder} supplies the "after" and folds the diff into
     * the same step as any block edit from that tick.</p>
     *
     * <p>Hooking here rather than at each leaf {@code executes} means the whole
     * editor command tree is covered — including subcommands added later.</p>
     */
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String command = event.getParseResults().getReader().getString();
        if (!isEditorCommand(command)) return;
        EditorEditRecorder.notePendingConfig(player, labelFor(command));
    }

    /**
     * Does this command line drive the editor? Matches the {@code /dungeontrain}
     * root and its {@code dt} alias.
     *
     * <p>{@code undo} and {@code redo} are excluded: arming a snapshot for them
     * would record the undo's own file writes as a fresh step, so the next
     * Ctrl+Z would undo the undo and the history would never move backwards.</p>
     */
    private static boolean isEditorCommand(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        c = c.trim();
        if (!c.startsWith("dungeontrain") && !c.startsWith("dt ")) return false;
        String rest = c.substring(c.indexOf(' ') + 1).trim();
        return !rest.startsWith("editor undo") && !rest.startsWith("editor redo");
    }

    /** Short human name for the action bar — the subcommand, minus the root. */
    private static String labelFor(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = c.trim().split("\\s+");
        if (parts.length < 2) return "Editor";
        // "dungeontrain editor weight inc" reads best as "editor weight"; two
        // tokens is enough to tell one menu action from another.
        return parts.length >= 3 && !parts[1].equals("save") && !parts[1].equals("reset")
            ? parts[1] + " " + parts[2]
            : parts[1];
    }
}
