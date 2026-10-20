package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.BurnableBookTag;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * "Nothing But Books" — granted when the player's 27 main-storage inventory
 * slots hold nothing but mod-authored story books, at least
 * {@value #MAIN_STORAGE_SLOTS} of them in all. Deliberately scoped to main
 * storage only (indices {@value #MAIN_STORAGE_START}–{@value #MAIN_STORAGE_END}
 * of {@link Inventory}) — hotbar (0–8), armor (36–39) and offhand (40) don't
 * count, matching the "your whole inventory" framing.
 *
 * <p>Stacks count by their size. Written books stack to 16, and identical
 * unresolved community-book placeholders merge when shift-clicked out of a
 * chest, so a player who took well over 27 books from a book room could end up
 * with gaps in the grid and still be refused — that is exactly what happened.
 * Nine stacks of three and eighteen empty slots now pass, just as 27 singles
 * always did. A slot holding anything that is not a story book still fails.</p>
 *
 * <p>A stack counts as a "story book" when it's a vanilla
 * {@link Items#WRITTEN_BOOK} that {@link BurnableBookTag#isBurnable} recognizes
 * as one of the mod's burn-after-reading kinds — every content stamp the mod
 * hands out, from starting/random/shared/narrative books through Death Notes,
 * Love Notes, leaderboard books and Faulthurst stat notes. Delegating to that
 * single predicate means this advancement can't quietly fall behind as new
 * burnable book kinds are added. A vanilla empty {@code writable_book} (book &amp;
 * quill) or a plain unstamped written book (e.g. one the player wrote
 * themselves but that vanilla kept, unmarked) both fail every check and so
 * don't count — "empty books don't count".</p>
 *
 * <p>The advancement JSON
 * ({@code data/dungeontrain/advancement/dungeon_train/nothing_but_books.json})
 * carries a single {@code minecraft:impossible} criterion, so it never fires on
 * its own — we award it directly here, exactly like {@link FarStartAdvancement}
 * and {@link PacifistAdvancement}. "Every one of 27 slots matches a
 * multi-valued NBT-stamp predicate" isn't expressible as a single vanilla
 * trigger, so the direct-award pattern is the natural fit.</p>
 */
public final class NothingButBooksAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id of the advancement. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/nothing_but_books");

    /** First main-storage slot index in {@link Inventory} (0–8 is hotbar). */
    private static final int MAIN_STORAGE_START = 9;
    /** Last main-storage slot index in {@link Inventory} (inclusive; 36–40 is armor + offhand). */
    private static final int MAIN_STORAGE_END = 35;
    /** How many story books the main storage must hold in all, stacks counted by size. */
    static final int MAIN_STORAGE_SLOTS = MAIN_STORAGE_END - MAIN_STORAGE_START + 1;

    private NothingButBooksAdvancement() {}

    /**
     * True when {@code stack} is a mod-authored story book: a written book
     * that the mod's burn-after-reading mechanic ({@link BurnableBookTag})
     * recognizes. Vanilla writable books and unstamped written books both
     * return false.
     */
    static boolean isStoryBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.WRITTEN_BOOK) return false;
        return BurnableBookTag.isBurnable(stack);
    }

    /**
     * True when {@code mainStorage} — the stacks of the 27 main-storage slots, empty ones
     * included — holds nothing but story books per {@link #isStoryBook}, and at least
     * {@value #MAIN_STORAGE_SLOTS} of them counting each stack by its size.
     */
    static boolean countsAsFull(List<ItemStack> mainStorage) {
        if (mainStorage == null) return false;
        int books = 0;
        for (ItemStack stack : mainStorage) {
            if (stack == null || stack.isEmpty()) continue;
            if (!isStoryBook(stack)) return false;
            books += stack.getCount();
        }
        return books >= MAIN_STORAGE_SLOTS;
    }

    /**
     * {@link #countsAsFull} over {@code player}'s main-storage slots (indices
     * {@value #MAIN_STORAGE_START} through {@value #MAIN_STORAGE_END}, inclusive).
     */
    private static boolean hasFullBookInventory(ServerPlayer player) {
        Inventory inv = player.getInventory();
        List<ItemStack> mainStorage = new ArrayList<>(MAIN_STORAGE_SLOTS);
        for (int i = MAIN_STORAGE_START; i <= MAIN_STORAGE_END; i++) {
            mainStorage.add(inv.getItem(i));
        }
        return countsAsFull(mainStorage);
    }

    /**
     * Evaluate the condition for {@code player} and award the advancement if
     * met.
     */
    public static void checkAndGrant(ServerPlayer player) {
        if (!hasFullBookInventory(player)) return;
        grant(player);
    }

    /**
     * Grant "Nothing But Books" to {@code player}. Idempotent: returns early
     * when the advancement data isn't loaded or it's already earned, then
     * awards each criterion key (just the single {@code impossible} criterion).
     */
    public static void grant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(ID);
        if (self == null) return; // advancement data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return; // already earned

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted 'Nothing But Books' (filled main inventory with story books) to {}",
                player.getName().getString());
        }
    }
}
