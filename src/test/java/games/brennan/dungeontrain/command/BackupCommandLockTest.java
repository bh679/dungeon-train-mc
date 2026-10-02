package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backup commands must be unusable by anyone, and nothing else may be touched. */
class BackupCommandLockTest {

    private static LiteralArgumentBuilder<CommandSourceStack> open(String name) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(name)
            .requires(source -> true)
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("restore").executes(ctx -> 1));
    }

    @Test
    void closesEveryBackupRootAndLeavesOtherCommandsAlone() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(open("dungeonbackup"));
        dispatcher.register(open("dtrestore"));
        dispatcher.register(open("dtrebuild"));

        int closed = BackupCommandLock.close(dispatcher);

        assertEquals(2, closed);
        // Brigadier parses a command only through nodes its source canUse — a false requirement
        // is "unknown command", and the node is left out of the tree sent to clients.
        assertFalse(dispatcher.getRoot().getChild("dungeonbackup").canUse(null));
        assertFalse(dispatcher.getRoot().getChild("dtrestore").canUse(null));
        assertTrue(dispatcher.getRoot().getChild("dtrebuild").canUse(null));
    }

    @Test
    void aDispatcherWithoutTheCommandsIsANoOp() {
        // Dungeon Backup 0.3.0 registers nothing.
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(open("dtrebuild"));

        assertEquals(0, BackupCommandLock.close(dispatcher));
    }
}
