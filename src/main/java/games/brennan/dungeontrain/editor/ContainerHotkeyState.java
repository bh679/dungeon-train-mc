package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side held-state for the per-player "container contents" key.
 * Mirrors {@link VariantHotkeyState} for the C-key. Currently only used
 * for symmetry with the Z-key flow — no gating logic depends on it yet,
 * but having the held-state available keeps the door open for future
 * "hold C and click to add" flows.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ContainerHotkeyState {

    private static final Set<UUID> HELD = ConcurrentHashMap.newKeySet();

    private ContainerHotkeyState() {}

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
