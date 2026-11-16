package games.brennan.dungeontrain.provenance;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.id.CarriageIds;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Writes in the first real holder of each piece of gear ({@link ItemProvenance}): once a second over
 * every online player's inventory and cursor, and at once for a craft or a trade. Also marks the gear
 * a mob drops as new, with the carriage it was spawned in.
 *
 * <p>Cosmetic, so every entry point is no-throw: a failure leaves the item as it was.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ProvenanceSweep {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /** The old vault-find lines, moved into the data the first time such an item is held. */
    static final String LORE_SERIAL = "lore.dungeontrain.vault_find.serial";
    static final String LORE_PULLED_BY = "lore.dungeontrain.vault_find.pulled_by";

    private ProvenanceSweep() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) return;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!isRealPlayer(player)) continue;
                Inventory inventory = player.getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) claim(player, inventory.getItem(i));
                claim(player, player.containerMenu.getCarried());
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] item provenance sweep failed: {}", t.toString());
        }
    }

    /** A crafted item is the crafter's; AdventureItemNames may already name them in its description. */
    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player) || !isRealPlayer(player)) return;
            ItemStack stack = event.getCrafting();
            if (!ItemProvenance.eligible(stack) || ItemProvenance.hasHolder(stack)) return;
            ItemProvenance.markHow(stack, ItemProvenance.How.MADE);
            // AIN writes its "Crafted by <name>" line at the head of the same take, before this event.
            if (namesPlayer(stack, player.getGameProfile().getName())) ItemProvenance.markAinCredited(stack);
            claim(player, stack);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] item provenance craft failed: {}", t.toString());
        }
    }

    /** From {@code MerchantResultSlotExtraCostsMixin}: a bought item is the buyer's. */
    public static void onBought(Player player, ItemStack stack) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || !isRealPlayer(serverPlayer)) return;
            ItemProvenance.markHow(stack, ItemProvenance.How.BOUGHT);
            claim(serverPlayer, stack);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] item provenance trade failed: {}", t.toString());
        }
    }

    /** A mob's dropped gear is new, from the carriage the mob was spawned in when it was. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMobDrops(LivingDropsEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (entity instanceof Player || entity.level().isClientSide()) return;
            OptionalInt pIdx = spawnCarriageOf(entity);
            for (ItemEntity drop : event.getDrops()) ItemProvenance.markFresh(drop.getItem(), pIdx);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] item provenance drops failed: {}", t.toString());
        }
    }

    /** From the echo drop and gift hooks: gear a player mob lets go of while alive is new too. */
    public static void markMobGear(LivingEntity mob, ItemStack stack) {
        try {
            ItemProvenance.markFresh(stack, spawnCarriageOf(mob));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] item provenance mob gear failed: {}", t.toString());
        }
    }

    /**
     * Write {@code player} in as the holder of {@code stack}, or move an old vault find's lore into
     * the data. Does nothing to an item that already has a holder.
     */
    public static void claim(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !ItemProvenance.eligible(stack) || ItemProvenance.hasHolder(stack)) return;
        if (migrateVaultLore(stack)) return;
        OptionalInt pIdx = ItemProvenance.pIdx(stack);
        Optional<String> carriage = pIdx.isPresent()
            ? Optional.of(CarriageIds.idFor(player.serverLevel(), pIdx.getAsInt()).id().format())
            : Optional.empty();
        ItemProvenance.claim(stack, player.getUUID(), player.getGameProfile().getName(),
            LocalDate.now(ZoneOffset.UTC).toEpochDay(), carriage,
            player.getRandom().nextInt(ItemProvenance.OLD_VARIANTS));
    }

    /**
     * A vault find stamped before this data existed carries "Pulled by Steve, 2026-11-07" in its
     * description: take the name and date from it and drop both old lines, whose number already
     * lives in the vault find's data.
     */
    static boolean migrateVaultLore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        String name = null;
        OptionalLong day = OptionalLong.empty();
        List<Component> kept = new ArrayList<>();
        for (Component line : lore.lines()) {
            if (line.getContents() instanceof TranslatableContents t) {
                if (LORE_PULLED_BY.equals(t.getKey())) {
                    Object[] args = t.getArgs();
                    if (args.length >= 2) {
                        name = textOf(args[0]);
                        day = ItemProvenance.epochDayOf(textOf(args[1]));
                    }
                    continue;
                }
                if (LORE_SERIAL.equals(t.getKey())) continue;
            }
            kept.add(line);
        }
        if (name == null || day.isEmpty()) return false;
        ItemProvenance.claimMigrated(stack, name, day.getAsLong());
        stack.set(DataComponents.LORE, new ItemLore(kept));
        return true;
    }

    private static String textOf(Object arg) {
        return arg instanceof Component c ? c.getString() : String.valueOf(arg);
    }

    private static boolean namesPlayer(ItemStack stack, String name) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || name.isEmpty()) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains(name)) return true;
        }
        return false;
    }

    private static OptionalInt spawnCarriageOf(LivingEntity entity) {
        String key = CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX;
        return entity.getPersistentData().contains(key)
            ? OptionalInt.of(entity.getPersistentData().getInt(key))
            : OptionalInt.empty();
    }

    private static boolean isRealPlayer(ServerPlayer player) {
        return !(player instanceof FakePlayer);
    }
}
