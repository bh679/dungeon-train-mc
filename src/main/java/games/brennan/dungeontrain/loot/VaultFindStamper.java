package games.brennan.dungeontrain.loot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Puts the relay's number on vault finds ({@link VaultFinds}) once it is in: at the vault's eject when
 * the answer beat it (the usual case), else on the next once-a-second sweep that finds the stack in an
 * online player's inventory, ender chest, open container or cursor. Does nothing while no number is
 * waiting.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VaultFindStamper {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SWEEP_INTERVAL_TICKS = 20;

    private VaultFindStamper() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) return;
        sweep(server);
    }

    /** From {@code VaultEjectMixin}: number the stack about to leave the vault, if its number is in. */
    public static void onEject(ServerLevel level, ItemStack stack) {
        try {
            VaultFindSerials serials = VaultFindSerials.get(level.getServer());
            if (!serials.isEmpty()) tryStamp(stack, serials);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] vault find eject stamp failed: {}", t.toString());
        }
    }

    static void sweep(MinecraftServer server) {
        try {
            VaultFindSerials serials = VaultFindSerials.get(server);
            if (serials.isEmpty()) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                stampAll(player.getInventory(), serials);
                stampAll(player.getEnderChestInventory(), serials);
                for (Slot slot : player.containerMenu.slots) tryStamp(slot.getItem(), serials);
                tryStamp(player.containerMenu.getCarried(), serials);
                if (serials.isEmpty()) return;
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] vault find sweep failed: {}", t.toString());
        }
    }

    private static void stampAll(Container container, VaultFindSerials serials) {
        for (int i = 0; i < container.getContainerSize(); i++) tryStamp(container.getItem(i), serials);
    }

    /** Stamp {@code stack} when it carries a claim whose number is waiting; the number is then spent. */
    static boolean tryStamp(ItemStack stack, VaultFindSerials serials) {
        if (stack.isEmpty()) return false;
        Optional<UUID> claim = VaultFinds.pendingClaim(stack);
        if (claim.isEmpty()) return false;
        Integer serial = serials.peek(claim.get());
        if (serial == null) return false;
        VaultFinds.stamp(stack, serial);
        serials.remove(claim.get());
        return true;
    }
}
