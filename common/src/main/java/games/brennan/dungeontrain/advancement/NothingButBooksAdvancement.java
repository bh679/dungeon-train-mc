package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.SharedBookFoundTag;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * "Nothing But Books" — granted when every one of the player's 27 main-storage
 * inventory slots holds a mod-authored story book. Deliberately scoped to main
 * storage only (indices {@value #MAIN_STORAGE_START}–{@value #MAIN_STORAGE_END}
 * of {@link Inventory}) — hotbar (0–8), armor (36–39) and offhand (40) don't
 * count, matching the "your whole inventory" framing.
 *
 * <p>A stack counts as a "story book" when it's a vanilla
 * {@link Items#WRITTEN_BOOK} carrying at least one of the mod's own content
 * stamps — {@link NarrativeBookTag}, {@link StartingBookTag},
 * {@link RandomBookTag}, or {@link SharedBookFoundTag}. A vanilla empty
 * {@code writable_book} (book & quill) or a plain unstamped written book (e.g.
 * one the player wrote themselves) both fail every check and so don't count —
 * "empty books don't count".</p>
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
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/nothing_but_books");

    /** First main-storage slot index in {@link Inventory} (0–8 is hotbar). */
    private static final int MAIN_STORAGE_START = 9;
    /** Last main-storage slot index in {@link Inventory} (inclusive; 36–40 is armor + offhand). */
    private static final int MAIN_STORAGE_END = 35;

    private NothingButBooksAdvancement() {}

    /**
     * True when {@code stack} is a mod-authored story book: a written book
     * carrying at least one of the mod's own content stamps. Vanilla writable
     * books and unstamped written books both return false.
     */
    static boolean isStoryBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.WRITTEN_BOOK) return false;
        return NarrativeBookTag.read(stack).isPresent()
            || StartingBookTag.isStartingBook(stack)
            || RandomBookTag.read(stack).isPresent()
            || SharedBookFoundTag.isFound(stack);
    }

    /**
     * True when every main-storage slot (indices {@value #MAIN_STORAGE_START}
     * through {@value #MAIN_STORAGE_END}, inclusive) of {@code player}'s
     * inventory holds a story book per {@link #isStoryBook}.
     */
    private static boolean hasFullBookInventory(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = MAIN_STORAGE_START; i <= MAIN_STORAGE_END; i++) {
            if (!isStoryBook(inv.getItem(i))) return false;
        }
        return true;
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
