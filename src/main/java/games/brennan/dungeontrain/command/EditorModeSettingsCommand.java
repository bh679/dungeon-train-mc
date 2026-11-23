package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.editor.EditorModeSetting;
import games.brennan.dungeontrain.editor.EditorModeSettingsService;
import games.brennan.dungeontrain.editor.EditorSettingMode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code dungeontrain editor} nodes for the per-mode Settings ({@link EditorModeSetting}):
 * {@code observers | redstone | tnt | fluids | autofly  on|off  [editor|test]} and
 * {@code mobs  blocks|live  [editor|test]}, plus {@code testmobs frozen|live} kept as the old name for
 * {@code mobs … test}.
 *
 * <p>The X menu's Settings rows always name the mode, so a click never lands on the other set. Typed
 * without one, a command sets the mode the player is in: the test values while standing in a
 * Test-the-Carriage copy, the editing values anywhere else.</p>
 */
public final class EditorModeSettingsCommand {

    private static final String MENU = "gui.dungeontrain.editor_menu.";

    private EditorModeSettingsCommand() {}

    /** Every node, for {@code EditorCommand.build} to attach under {@code editor}. */
    public static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        List<LiteralArgumentBuilder<CommandSourceStack>> out = new ArrayList<>();
        out.add(onOff(EditorModeSetting.OBSERVERS));
        out.add(onOff(EditorModeSetting.REDSTONE));
        out.add(onOff(EditorModeSetting.TNT));
        out.add(onOff(EditorModeSetting.FLUID_FLOW));
        out.add(onOff(EditorModeSetting.AUTO_FLY));
        out.add(choice("mobs", "blocks", "live", null));
        out.add(choice("testmobs", "frozen", "live", EditorSettingMode.TEST));
        return out;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> onOff(EditorModeSetting setting) {
        return Commands.literal(setting.id())
            .then(valueNode("on", setting, true, null))
            .then(valueNode("off", setting, false, null));
    }

    /** A two-word node for Mobs; {@code fixed} pins the mode (the {@code testmobs} alias). */
    private static LiteralArgumentBuilder<CommandSourceStack> choice(String literal, String offWord, String onWord,
                                                                     EditorSettingMode fixed) {
        return Commands.literal(literal)
            .then(valueNode(offWord, EditorModeSetting.MOBS_LIVE, false, fixed))
            .then(valueNode(onWord, EditorModeSetting.MOBS_LIVE, true, fixed));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> valueNode(String word, EditorModeSetting setting,
                                                                        boolean value, EditorSettingMode fixed) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(word)
            .executes(ctx -> run(ctx.getSource(), setting, value, fixed));
        if (fixed == null) {
            for (EditorSettingMode mode : EditorSettingMode.values()) {
                node.then(Commands.literal(mode.id()).executes(ctx -> run(ctx.getSource(), setting, value, mode)));
            }
        }
        return node;
    }

    private static int run(CommandSourceStack source, EditorModeSetting setting, boolean value,
                           EditorSettingMode explicit) {
        EditorSettingMode mode = explicit != null ? explicit : modeOf(source);
        EditorModeSettingsService.set(source.getServer(), mode, setting, value);
        Component feedback = mode == EditorSettingMode.TEST ? testFeedback(setting, value) : editorFeedback(setting, value);
        source.sendSuccess(() -> feedback.copy().withStyle(styleFor(setting, value)), false);
        return 1;
    }

    private static EditorSettingMode modeOf(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? EditorSettingMode.EDITOR : EditorSettingMode.of(player);
    }

    /** Green for the normal-play state, yellow for the one that changes how a plot behaves. */
    private static ChatFormatting styleFor(EditorModeSetting setting, boolean value) {
        boolean normal = setting == EditorModeSetting.MOBS_LIVE ? !value : value;
        return normal ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
    }

    /** The editing-mode lines each setting has always had. */
    static Component editorFeedback(EditorModeSetting setting, boolean value) {
        String key = switch (setting) {
            case OBSERVERS -> value ? "chat.dungeontrain.editor.observers_observers_plots_pulse"
                                    : "chat.dungeontrain.editor.observers_off_observers_plots";
            case REDSTONE -> value ? "chat.dungeontrain.editor.redstone_on" : "chat.dungeontrain.editor.redstone_off";
            case TNT -> value ? "chat.dungeontrain.editor.tnt_on" : "chat.dungeontrain.editor.tnt_off";
            case FLUID_FLOW -> value ? "chat.dungeontrain.editor.fluid_flow_on" : "chat.dungeontrain.editor.fluid_flow_off";
            case AUTO_FLY -> value ? "chat.dungeontrain.editor.auto_fly_on" : "chat.dungeontrain.editor.auto_fly_off";
            case MOBS_LIVE -> value ? "chat.dungeontrain.editor.mobs_live_spawn_eggs"
                                    : "chat.dungeontrain.editor.mobs_blocks_spawn_eggs";
        };
        return Component.translatable(key);
    }

    /** One line for every setting in test mode: which, what it is now, and that editing keeps its own. */
    static Component testFeedback(EditorModeSetting setting, boolean value) {
        return Component.translatable("chat.dungeontrain.editor.test_setting_value",
            Component.translatable(MENU + labelKey(setting)),
            Component.translatable(MENU + valueKey(setting, value)));
    }

    /** The Settings row label's key, after {@code gui.dungeontrain.editor_menu.}. */
    public static String labelKey(EditorModeSetting setting) {
        return switch (setting) {
            case OBSERVERS -> "editor.observers";
            case REDSTONE -> "editor.redstone";
            case TNT -> "editor.tnt";
            case FLUID_FLOW -> "editor.fluid_flow";
            case AUTO_FLY -> "editor.auto_fly";
            case MOBS_LIVE -> "editor.mobs";
        };
    }

    private static String valueKey(EditorModeSetting setting, boolean value) {
        if (setting == EditorModeSetting.MOBS_LIVE) return value ? "editor.mobs_live" : "editor.mobs_blocks";
        return value ? "common.on" : "common.off";
    }
}
