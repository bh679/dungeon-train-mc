package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * "The Far Start" — granted when a player carries their starting book past
 * {@link #CARRIAGE_THRESHOLD} carriages in a single life. It mirrors the
 * {@code carts_100} ("Dungeon Train Explorer") mechanic — the same
 * travelled-carriage counter — but at a higher threshold and with one extra
 * condition: the starting book delivered at spawn must still be in the
 * player's inventory at the moment the carriage counter crosses the threshold
 * (reading the book burns it, so this rewards carrying it the long way without
 * opening it).
 *
 * <p>Driven from {@link games.brennan.dungeontrain.event.AchievementEvents#notifyCartAdvance}
 * off the same {@code Math.abs(PlayerRunState.travelledCarriageIndex())}
 * counter that fires {@code carts_100}, so the two stay coherent — both reset
 * on the player's death, and the starting book also re-spawns each life.</p>
 *
 * <p>The advancement JSON
 * ({@code data/dungeontrain/advancement/dungeon_train/the_far_start.json})
 * carries a single {@code minecraft:impossible} criterion, so it never fires on
 * its own — we award it directly here, exactly like {@link SurveyAdvancement}
 * and {@link CompletionistAdvancement}. The condition (a counter threshold AND
 * "holds a specifically-tagged item") isn't expressible as one standard
 * trigger, so the direct-award pattern is the natural fit.</p>
 */
public final class FarStartAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id of the far-start advancement. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/the_far_start");

    /**
     * Carriages-in-this-life the player must traverse while still carrying the
     * starting book. Sits above {@code carts_100}'s 100-carriage milestone, so
     * "The Far Start" is a deliberately longer haul with the (unread) book in
     * hand.
     */
    public static final int CARRIAGE_THRESHOLD = 150;

    private FarStartAdvancement() {}

    /**
     * Pure gating decision, split out so it can be unit-tested without a
     * {@link ServerPlayer}. Grant when the player has traversed at least
     * {@link #CARRIAGE_THRESHOLD} carriages this life AND is still carrying a
     * starting book.
     */
    static boolean shouldGrant(int travelledCarriagesAbs, boolean holdsStartingBook) {
        return travelledCarriagesAbs >= CARRIAGE_THRESHOLD && holdsStartingBook;
    }

    /**
     * Evaluate the condition for {@code player} and award the advancement if
     * met. Cheap in the common case: the threshold check short-circuits before
     * the inventory scan, so the scan only runs once a player is genuinely past
     * carriage {@link #CARRIAGE_THRESHOLD}.
     *
     * @param travelledCarriagesAbs {@code Math.abs(PlayerRunState.travelledCarriageIndex())}
     *                              — the same value {@code carts_100} keys off.
     */
    public static void checkAndGrant(ServerPlayer player, int travelledCarriagesAbs) {
        if (travelledCarriagesAbs < CARRIAGE_THRESHOLD) return;
        if (!holdsStartingBook(player)) return;
        grant(player);
    }

    /**
     * True when {@code player} is carrying a starting book anywhere in their
     * inventory (hands included). Non-destructive — unlike
     * {@code StartingBookEvents.findAndRemoveBurnableBook}, this only reads.
     */
    private static boolean holdsStartingBook(ServerPlayer player) {
        if (StartingBookTag.isStartingBook(player.getMainHandItem())) return true;
        if (StartingBookTag.isStartingBook(player.getOffhandItem())) return true;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (StartingBookTag.isStartingBook(inv.getItem(i))) return true;
        }
        return false;
    }

    /**
     * Grant "The Far Start" to {@code player}. Idempotent: returns early when
     * the advancement data isn't loaded or it's already earned, then awards each
     * criterion key (just the single {@code impossible} criterion).
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
            LOGGER.info("[DungeonTrain] Granted 'The Far Start' (carried starting book past {} carriages) to {}",
                CARRIAGE_THRESHOLD, player.getName().getString());
        }
    }
}
