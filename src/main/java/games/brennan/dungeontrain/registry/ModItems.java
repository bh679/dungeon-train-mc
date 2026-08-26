package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side item registry. Wires up custom items via the mod-event bus during
 * construction.
 *
 * <p>Currently registers only {@link VariantClipboardItem}, the per-cell
 * variant snippet produced by the block-variant menu's Copy button. Prefab
 * tab entries are vanilla {@code BlockItem} stacks with discriminator NBT
 * (see {@link games.brennan.dungeontrain.event.PrefabUseHandler}) — no
 * mod-side item needed for those.</p>
 *
 * <p>The variant clipboard is hooked into the Creative inventory's
 * TOOLS_AND_UTILITIES tab via {@link BuildCreativeModeTabContentsEvent} so
 * authors can grab a blank clipboard for testing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DungeonTrain.MOD_ID);

    public static final DeferredItem<Item> VARIANT_CLIPBOARD = ITEMS.register(
        "variant_clipboard",
        () -> new VariantClipboardItem(new Item.Properties().stacksTo(1))
    );

    /**
     * Editor-only placeholder. Never appears in survival inventories — at
     * chest spawn time {@code ContainerContentsRoller.rollItemStack}
     * intercepts entries with this item id and substitutes a stamped vanilla
     * {@code WRITTEN_BOOK} rolled from
     * {@link games.brennan.dungeontrain.narrative.RandomBookRegistry}.
     */
    public static final DeferredItem<Item> RANDOM_BOOK = ITEMS.register(
        "random_book",
        () -> new Item(new Item.Properties().stacksTo(1))
    );

    /**
     * Editor-only placeholder, sibling to {@link #RANDOM_BOOK}. Never appears
     * in survival inventories — at chest spawn time {@code
     * ContainerContentsRoller.rollItemStack} intercepts entries with this item
     * id and substitutes a written book drawn <b>exclusively from
     * player-written community books</b>
     * ({@link games.brennan.dungeontrain.narrative.SharedBookPool}). When the
     * shared pool is disabled/empty/offline it falls back to a hardcoded local
     * book from
     * {@link games.brennan.dungeontrain.narrative.RandomBookRegistry} so the
     * slot is never wasted.
     */
    public static final DeferredItem<Item> RANDOM_PLAYERBOOK = ITEMS.register(
        "random_playerbook",
        () -> new Item(new Item.Properties().stacksTo(1))
    );

    /**
     * Editor-only placeholder, third sibling to {@link #RANDOM_BOOK} and
     * {@link #RANDOM_PLAYERBOOK}. Substituted at chest spawn time for a
     * LEADERBOARD book — a ranked list of the top players in one category,
     * fetched from the relay
     * ({@link games.brennan.dungeontrain.narrative.LeaderboardPool}).
     *
     * <p>Placing this deliberately in the editor is the way to guarantee one;
     * in ordinary loot they also turn up as a share of
     * {@link #RANDOM_BOOK} rolls, which is what makes them feel like one of
     * the random books rather than a separate find. Either way the actual
     * board is chosen when a player first holds the stack — see
     * {@link games.brennan.dungeontrain.narrative.LeaderboardBookPendingTag}.
     * With no relay, the slot keeps the ordinary random book it baked as a
     * fallback, so it is never wasted.</p>
     */
    public static final DeferredItem<Item> RANDOM_LEADERBOARD_BOOK = ITEMS.register(
        "random_leaderboard_book",
        () -> new Item(new Item.Properties().stacksTo(1))
    );

    /**
     * Editor-only placeholder, sibling to {@link #RANDOM_LEADERBOARD_BOOK}. Substituted at chest
     * spawn time for a FAULTHURST STAT BOOK — a one-page note, signed by the mod's watching
     * narrator, naming one real number from the reader's current run
     * ({@link games.brennan.dungeontrain.narrative.RunStatBookFactory}).
     *
     * <p>Placing this deliberately in the editor guarantees one; in ordinary loot they also turn up
     * as a share of {@link #RANDOM_BOOK} rolls, which is what makes them feel like one of the random
     * books rather than a separate find.</p>
     *
     * <p>Unlike the leaderboard book this needs nothing fetched — the run it reports on is always
     * there — so the container bakes a real, readable, signed note immediately. What it cannot know
     * is the READER, so the number is filled in when the book first reaches a hand and kept current
     * until it is opened; see
     * {@link games.brennan.dungeontrain.narrative.RunStatBookTag}.</p>
     */
    public static final DeferredItem<Item> RANDOM_STAT_BOOK = ITEMS.register(
        "random_stat_book",
        () -> new Item(new Item.Properties().stacksTo(1))
    );

    private ModItems() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /**
     * Add the variant clipboard to the Creative inventory's TOOLS_AND_UTILITIES
     * tab. The narrative-side {@link #RANDOM_BOOK} placeholder lives in the
     * mod's own NARRATIVE tab (see {@link ModCreativeTabs#NARRATIVE}), not
     * here — narrative authoring deserves its own grouping rather than being
     * mixed in with editor tools.
     */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(VARIANT_CLIPBOARD.get());
        }
    }
}
