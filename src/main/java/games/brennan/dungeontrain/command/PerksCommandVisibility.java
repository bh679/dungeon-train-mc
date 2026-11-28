package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Keeps {@code /dungeontrain perks} out of a player's suggestions until their run is Free Play, while
 * still letting it run when typed in full on a clean run (which then puts the run into Free Play).
 *
 * <p>A Brigadier requirement decides both: what {@code Commands.sendCommands} sends a client, and
 * whether the command parses. A requirement that fails on a clean run would also stop the command
 * running, so the Free Play half applies only while the tree is being built for a client, which
 * {@code CommandsSendTreeMixin} marks. Without the mixin the command is simply always offered to
 * operators.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerksCommandVisibility {

    private static final ThreadLocal<Boolean> BUILDING_TREE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PerksCommandVisibility() {}

    /** {@code CommandsSendTreeMixin}: a client's command tree is being built on this thread. */
    public static void beginTree() {
        BUILDING_TREE.set(Boolean.TRUE);
    }

    public static void endTree() {
        BUILDING_TREE.set(Boolean.FALSE);
    }

    static boolean buildingTree() {
        return BUILDING_TREE.get();
    }

    /** The {@code perks} node's requirement. */
    static boolean mayUse(CommandSourceStack source) {
        if (!source.hasPermission(2)) return false;
        ServerPlayer player = source.getPlayer();
        return shown(buildingTree(), player != null && RunIntegrity.isCheated(player));
    }

    /** Parsing always passes; the tree sent to a client carries the node only in Free Play. */
    static boolean shown(boolean buildingTree, boolean freePlay) {
        return !buildingTree || freePlay;
    }

    /** Re-send every online player's tree, so the suggestion appears once the world is Free Play. */
    public static void refreshAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    /**
     * Session-only Free Play (a changed config, custom content) is often decided during login, after
     * the login tree went out: send this player's tree once more a tick later.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (!player.hasDisconnected()) server.getCommands().sendCommands(player);
        }));
    }
}
