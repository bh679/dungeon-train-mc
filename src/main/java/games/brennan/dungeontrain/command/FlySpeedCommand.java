package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /dungeontrain flyspeed <multiplier>} (and {@code /dt flyspeed}) — sets the executing
 * player's creative fly speed as a multiple of vanilla's. Vanilla has no command for this: fly
 * speed is an ability ({@code Abilities.flyingSpeed}), not an attribute. {@code 1} restores
 * vanilla. The value is saved with the player, so it survives a relog.
 */
public final class FlySpeedCommand {

    /** Vanilla's {@code Abilities.flyingSpeed} default. */
    public static final float VANILLA_FLY_SPEED = 0.05f;
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
        setMultiplier(player, multiplier);
        source.sendSuccess(() -> Component.literal("[DungeonTrain] Fly speed set to " + multiplier + "×")
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Sets fly speed to {@code multiplier} × vanilla and syncs it to the player's client. */
    public static void setMultiplier(ServerPlayer player, float multiplier) {
        player.getAbilities().setFlyingSpeed(VANILLA_FLY_SPEED * multiplier);
        player.onUpdateAbilities();
    }
}
