package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.cheat.AisDataIntegrity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The {@code /fixaisconfig} command: the one-click "action to fix" behind the
 * AIS-data Free Play notice (see {@link AisDataIntegrity}). Rewrites
 * {@code config/adventureitemstats.properties} with the AIS defaults — never
 * caller input, so it is safe to leave open to everyone
 * ({@code requires(s -> true)}, mirroring {@code /bug}): the worst any player
 * can do with it is restore the intended settings. Whitelisted in
 * {@code CommandAllowlist} so running it never taints a run.
 *
 * <p>AIS loads its config once at game launch, so the session Free Play flag
 * stays set until the game (or dedicated server) restarts — the success message
 * says exactly that.</p>
 */
public final class FixAisConfigCommand {

    private FixAisConfigCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("fixaisconfig")
                        .requires(source -> true) // everyone, any game mode — see class doc
                        .executes(FixAisConfigCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean hadDeviations = AisDataIntegrity.isSessionFreePlay();
        AisDataIntegrity.RestoreResult result = AisDataIntegrity.restoreDefaults(FMLPaths.CONFIGDIR.get());
        if (!result.success()) {
            source.sendFailure(Component.translatable("command.dungeontrain.fix_ais.fail"));
            return 0;
        }
        String key = hadDeviations
            ? "command.dungeontrain.fix_ais.success"
            : "command.dungeontrain.fix_ais.already_default";
        source.sendSuccess(() -> Component.translatable(key).withStyle(ChatFormatting.GREEN), false);
        if (result.backup() != null) {
            String backupName = result.backup().getFileName().toString();
            source.sendSuccess(() -> Component.translatable(
                "command.dungeontrain.fix_ais.backup", backupName)
                .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }
}
