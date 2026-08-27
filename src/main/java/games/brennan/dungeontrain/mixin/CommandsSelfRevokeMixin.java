package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.StartAgainAdvancement;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a player who already holds "Everything Burrito" run
 * {@code /advancement revoke @s everything} without cheats — the command that earns
 * {@link StartAgainAdvancement "It's Not That Simple"}.
 *
 * <p><b>Why a mixin is needed.</b> Vanilla gates the whole {@code advancement} node behind
 * {@code .requires(source -> source.hasPermission(2))} ({@code AdvancementCommands.register}), so
 * for an ordinary player the node isn't in the command tree and the command never parses —
 * "Unknown or incomplete command". That alone would make the advancement unearnable. Turning
 * cheats <em>on</em> doesn't rescue it either: {@code OperatorIntegrity} treats cheats being
 * <em>available</em> as Free Play, so the run is tainted before the command is typed. Opening this
 * one command to a capstone-holder is what makes the advancement earnable by a clean survival
 * player at all.</p>
 *
 * <p><b>Why HEAD of {@code performPrefixedCommand}.</b> It is the last point that still holds the
 * raw command string, immediately before {@code dispatcher.parse} rejects it. Relaxing the
 * {@code requires} predicate instead would open {@code grant} and {@code set} too — Brigadier
 * requirements live on the parent node and are fixed at build time, so there is no way to relax
 * the root without relaxing the whole subtree.</p>
 *
 * <p><b>Deliberately narrow.</b> Four guards, cheapest first: the exact command string (via
 * {@link CommandAllowlist#isSelfRevokeEverything}, the same classifier the cheat allowlist and the
 * advancement's arming share, so all three agree by construction), a player source, <em>not</em>
 * already an operator (ops keep the untouched vanilla path), and the capstone actually earned.
 * It grants no other command and no lasting permission.</p>
 */
@Mixin(Commands.class)
public abstract class CommandsSelfRevokeMixin {

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$selfRevokeWithoutCheats(CommandSourceStack source, String command, CallbackInfo ci) {
        if (!CommandAllowlist.isSelfRevokeEverything(command)) return;
        ServerPlayer player = source.getPlayer();
        if (player == null) return;                 // console / command block / function
        if (source.hasPermission(2)) return;        // an operator runs it the ordinary way
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AdvancementHolder capstone = server.getAdvancements().get(CompletionistAdvancement.ID);
        if (capstone == null) return;               // capstone data not loaded
        if (!player.getAdvancements().getOrStartProgress(capstone).isDone()) return; // not earned it

        StartAgainAdvancement.performSelfWipe(player, source);
        ci.cancel();
    }
}
