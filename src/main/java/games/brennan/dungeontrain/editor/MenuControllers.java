package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;

/**
 * The one list of every {@link PlayerMenuController}. The lifecycle paths loop over it — a
 * history step, a logout, a server stop — so adding a menu means adding it here and nowhere
 * else.
 *
 * <p>Order matters only for the history resync, which keeps the order
 * {@code EditorMenuResync} always used: the part menu first, the stage panel last.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MenuControllers {

    private static final List<PlayerMenuController<?>> ALL = List.of(
        PartPositionMenuController.INSTANCE,
        BlockVariantMenuController.INSTANCE,
        ContainerContentsMenuController.INSTANCE,
        TemplateBlocksMenuController.INSTANCE,
        TemplateBlockGroupsController.INSTANCE,
        StagePanelController.INSTANCE);

    private MenuControllers() {}

    public static List<PlayerMenuController<?>> all() {
        return ALL;
    }

    /** Every controller's answer to a history step for {@code player}. */
    public static void resyncAfterHistory(ServerPlayer player) {
        for (PlayerMenuController<?> c : ALL) c.resyncAfterHistory(player);
    }

    /** The player is leaving: every controller drops them. */
    public static void onLogout(ServerPlayer player) {
        for (PlayerMenuController<?> c : ALL) c.onLogout(player);
    }

    /** Server stop: every controller forgets everyone. */
    public static void clearAll() {
        for (PlayerMenuController<?> c : ALL) c.clearAll();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }
}
