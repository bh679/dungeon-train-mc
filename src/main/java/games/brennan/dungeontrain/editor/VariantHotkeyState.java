package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side held-state for the per-player "variant place" key. The client
 * sends a {@link games.brennan.dungeontrain.net.VariantHotkeyPacket} on press
 * and release; this class stores the set of players currently holding the
 * key. Replaces the previous {@code isShiftKeyDown} check in
 * {@link VariantBlockInteractions} so vanilla sneak no longer triggers
 * variant placement.
 *
 * <p>Cleanup on logout is automatic via the
 * {@link PlayerEvent.PlayerLoggedOutEvent} hook below — otherwise a stuck-down
 * key on disconnect would leave the player flagged forever.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VariantHotkeyState {

    private static final Set<UUID> HELD = ConcurrentHashMap.newKeySet();

    private VariantHotkeyState() {}

    public static void setHeld(ServerPlayer player, boolean held) {
        if (held) HELD.add(player.getUUID());
        else HELD.remove(player.getUUID());
    }

    public static boolean isHeld(ServerPlayer player) {
        return HELD.contains(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        HELD.remove(event.getEntity().getUUID());
    }
}
