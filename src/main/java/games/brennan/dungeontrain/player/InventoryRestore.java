package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Outcome;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Restore;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Stack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies an admin-queued, one-time inventory restore to a player (server thread only).
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li><b>Never into Free Play.</b> A run that {@link RunIntegrity#isCheated} gets nothing and the
 *       restore stays pending on the relay. Restoring never reads or clears {@code RUN_CHEATED}, so
 *       it can't launder a cheated run.</li>
 *   <li><b>Once per install.</b> The id is written to {@value #APPLIED_FILE} before any item moves,
 *       so a lost ack can't make it apply twice here.</li>
 *   <li><b>Nothing is deleted.</b> Each restored stack takes its snapshot slot; whatever the
 *       player held there is packed into "Before restore" chest items (see
 *       {@link RestorePlacement}).</li>
 * </ol>
 */
public final class InventoryRestore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String APPLIED_FILE = "restores-applied.txt";

    private static final Set<String> TOLD_WAITING = ConcurrentHashMap.newKeySet();

    private InventoryRestore() {}

    /** Apply {@code restore} to {@code player} if allowed, and ack the relay. */
    public static void apply(ServerPlayer player, Restore restore) {
        String name = player.getName().getString();
        if (RunIntegrity.isCheated(player)) {
            LOGGER.warn("[DungeonTrain] Inventory restore {} for {} held: this run is Free Play", restore.id(), name);
            // Once per restore per server run, not on every 5-minute poll.
            if (TOLD_WAITING.add(restore.id())) {
                player.sendSystemMessage(Component.translatable("chat.dungeontrain.restore.waiting")
                    .withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        Path dir = PlayerDataPaths.dir(PlayerDataPaths.OUTBOX);
        if (isApplied(dir, restore.id())) {
            LOGGER.info("[DungeonTrain] Inventory restore {} already applied on this install; re-acking", restore.id());
            InventoryRestoreClient.ack(player.getUUID(), restore.id(),
                new Outcome(true, "already_applied_here", 0, 0, 0));
            return;
        }
        if (!markApplied(dir, restore.id())) {
            // Without the marker a lost ack could apply it twice — wait for a writable disk instead.
            LOGGER.warn("[DungeonTrain] Inventory restore {} for {} held: couldn't record it as applied", restore.id(), name);
            return;
        }
        Outcome outcome = place(player, restore);
        LOGGER.info("[DungeonTrain] Inventory restore {} applied for {} ({}): placed={} displaced={} skipped={} xpLevels={}",
            restore.id(), name, player.getUUID(), outcome.placed(), outcome.displaced(), outcome.skipped(),
            restore.xpLevels());
        player.sendSystemMessage(Component.translatable(outcome.displaced() > 0
                ? "chat.dungeontrain.restore.applied_with_chest" : "chat.dungeontrain.restore.applied")
            .withStyle(ChatFormatting.GREEN));
        InventoryRestoreClient.ack(player.getUUID(), restore.id(), outcome);
    }

    private static Outcome place(ServerPlayer player, Restore restore) {
        HolderLookup.Provider registries = player.registryAccess();
        Inventory inv = player.getInventory();

        List<RestorePlacement.Target<ItemStack>> targets = new ArrayList<>();
        int unreadable = 0;
        for (Stack s : restore.all()) {
            ItemStack stack = parse(registries, s);
            if (stack.isEmpty()) {
                unreadable++;
                continue;
            }
            targets.add(new RestorePlacement.Target<>(s.slot(), stack));
        }

        RestorePlacement.Plan<ItemStack> plan = RestorePlacement.plan(targets, inv::getItem, ItemStack::isEmpty);
        for (Map.Entry<Integer, ItemStack> e : plan.placements().entrySet()) {
            inv.setItem(e.getKey(), e.getValue());
        }
        // Chests go in after the restored stacks so they only use slots the snapshot left free.
        for (List<ItemStack> group : RestorePlacement.packIntoChests(plan.displaced())) {
            inv.placeItemBackInInventory(chestOf(group));
        }
        if (restore.xpLevels() > player.experienceLevel) {
            player.setExperienceLevels(restore.xpLevels());
        }
        player.inventoryMenu.broadcastChanges();
        return new Outcome(true, null, plan.placements().size(), plan.displaced().size(),
            plan.skipped() + unreadable);
    }

    private static ItemStack parse(HolderLookup.Provider registries, Stack s) {
        try {
            return ItemStack.parse(registries, TagParser.parseTag(s.nbt())).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Inventory restore: skipping unreadable stack in slot {} ({}): {}",
                s.slot(), s.id(), e.toString());
            return ItemStack.EMPTY;
        }
    }

    /** A chest item that places down holding {@code stacks}. */
    private static ItemStack chestOf(List<ItemStack> stacks) {
        ItemStack chest = new ItemStack(Items.CHEST);
        chest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        chest.set(DataComponents.CUSTOM_NAME, Component.translatable("item.dungeontrain.restore_chest"));
        return chest;
    }

    static boolean isApplied(Path dir, String id) {
        Path file = dir.resolve(APPLIED_FILE);
        if (!Files.isRegularFile(file)) return false;
        try {
            return Files.readAllLines(file).stream().anyMatch(line -> line.trim().equals(id));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Inventory restore: couldn't read {}: {}", file, e.toString());
            // Unreadable marker: treat as applied so nothing can double-apply.
            return true;
        }
    }

    static boolean markApplied(Path dir, String id) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(APPLIED_FILE), id + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Inventory restore: couldn't record {} as applied: {}", id, e.toString());
            return false;
        }
    }
}
