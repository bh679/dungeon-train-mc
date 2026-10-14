package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.Set;

/**
 * The Live Feed Cassette and the Random Broadcast Camcorder placeholder stay registered — a TV tunes
 * itself by holding a cassette ({@code mixin.vista.TVBlockMixin}), and container templates may name the
 * placeholder for {@code editor.ContainerContentsRoller} to roll — but neither is ever a player's item.
 *
 * <p>The TV mixins stop the cassette leaving a TV by hand or hopper; this is the backstop for every
 * other way out. A dropped stack of either never enters the world (breaking a TV, a swap, a death),
 * and any copy in a player's inventory or on their cursor — older saves, {@code /give} — is removed
 * on login and once a second after.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class UnobtainableLiveItems {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final Set<String> IDS = Set.of("dungeontrain:live_cassette", "dungeontrain:random_live_headpiece");

    private static final int SWEEP_INTERVAL_TICKS = 20;

    private UnobtainableLiveItems() {}

    /** Id-level rule, split out so it is testable without a bootstrapped registry. */
    static boolean isUnobtainable(ResourceLocation id) {
        return id != null && IDS.contains(id.toString());
    }

    public static boolean isUnobtainable(ItemStack stack) {
        return !stack.isEmpty() && isUnobtainable(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ItemEntity drop && isUnobtainable(drop.getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sweep(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % SWEEP_INTERVAL_TICKS == 0) {
            sweep(player);
        }
    }

    private static void sweep(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int removed = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isUnobtainable(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }
        if (isUnobtainable(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            removed++;
        }
        if (removed > 0) {
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            LOGGER.info("[LiveFeed] Removed {} unobtainable Live Feed item stack(s) from {}",
                removed, player.getGameProfile().getName());
        }
    }
}
