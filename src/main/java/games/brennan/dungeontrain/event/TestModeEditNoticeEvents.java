package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.train.CarriageTestSession;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells an author who places or breaks a block inside a Test-the-Carriage copy that none of it will
 * be kept — the copy is swept away on Back and never reaches the template.
 *
 * <p>Both kinds of test count: {@link CarriageTestSession} (carriages, contents, whole rooms) and
 * {@link PortalTestSession} (dimensional carriages). The notice goes to the action bar and is
 * throttled per player, so steady building keeps it on screen without re-sending it every block.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TestModeEditNoticeEvents {

    static final String MESSAGE_KEY = "chat.dungeontrain.test_mode.nothing_saves";

    /** Game ticks between repeats — about the time vanilla keeps an action-bar line up. */
    static final long REPEAT_TICKS = 40L;

    private static final Map<UUID, Long> LAST_SHOWN = new ConcurrentHashMap<>();

    private TestModeEditNoticeEvents() {}

    // LOWEST: a handler that cancels the edit (builder protection) has run by then, and a refused
    // edit changed nothing worth warning about.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (event.getEntity() instanceof ServerPlayer player) notice(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        Player player = event.getPlayer();
        if (player instanceof ServerPlayer serverPlayer) notice(serverPlayer);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SHOWN.remove(event.getEntity().getUUID());
    }

    private static void notice(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!CarriageTestSession.has(id) && !PortalTestSession.has(id)) return;
        long now = player.serverLevel().getGameTime();
        if (!shouldNotify(LAST_SHOWN.get(id), now)) return;
        LAST_SHOWN.put(id, now);
        player.displayClientMessage(
            Component.translatable(MESSAGE_KEY).withStyle(ChatFormatting.YELLOW), true);
    }

    /**
     * Whether to show the notice again: always the first time, then once {@link #REPEAT_TICKS} have
     * passed. A clock that went backwards (a different dimension's game time) shows it too, rather
     * than going silent until it catches up.
     */
    static boolean shouldNotify(Long lastShownTick, long nowTick) {
        if (lastShownTick == null) return true;
        long elapsed = nowTick - lastShownTick;
        return elapsed < 0 || elapsed >= REPEAT_TICKS;
    }
}
