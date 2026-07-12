package games.brennan.dungeontrain.platform.event;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;

/**
 * Loader-neutral form of NeoForge's {@code CommandEvent} — fired on the server
 * thread after a command is parsed but BEFORE it executes. <b>Cancellable:</b>
 * this callback returns {@code true} to cancel the command (equivalent to
 * {@code CommandEvent.setCanceled(true)} — the command does not run). The bridge
 * iterates listeners and stops on the FIRST that returns {@code true}, mapping
 * that to the NeoForge event's cancellation — the same short-circuit contract as
 * a NeoForge cancellable event.
 *
 * @param parseResults the parsed command (matches {@code CommandEvent.getParseResults()})
 * @return {@code true} to cancel the command, {@code false} to let it proceed
 */
@FunctionalInterface
public interface DtCommandCallback {

    boolean onCommand(ParseResults<CommandSourceStack> parseResults);
}
