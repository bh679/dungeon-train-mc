package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.player.SprintFlyBoost;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /dungeontrain flyspeed <multiplier>} (and {@code /dt flyspeed}) — sets the executing
 * player's creative sprint-fly speed as a multiple of vanilla's. Only sprint-flying, and only its
 * horizontal speed: plain flight and up/down stay vanilla. {@code 1} restores vanilla. See
 * {@link SprintFlyBoost}.
 */
public final class FlySpeedCommand {

    private static final float MIN_MULTIPLIER = 0.1f;
    private static final float MAX_MULTIPLIER = 20f;

    private FlySpeedCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("flyspeed")
            .then(Commands.argument("multiplier", FloatArgumentType.floatArg(MIN_MULTIPLIER, MAX_MULTIPLIER))
                .executes(ctx -> run(ctx.getSource(), FloatArgumentType.getFloat(ctx, "multiplier"))));
    }

    private static int run(CommandSourceStack source, float multiplier) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SprintFlyBoost.set(player, multiplier);
        source.sendSuccess(() -> Component.literal("[DungeonTrain] Sprint-fly speed set to " + multiplier + "×")
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
