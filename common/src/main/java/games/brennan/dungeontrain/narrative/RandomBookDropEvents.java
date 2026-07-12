package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gives books dropped by breaking a vanilla bookshelf a small chance to be a
 * narrative {@link RandomBookFactory Random Book} instead of a plain
 * {@code minecraft:book}.
 *
 * <p>Library carriages are densely packed with {@code minecraft:bookshelf}
 * blocks, each of which drops three books when broken without Silk Touch. For
 * every such book we roll {@code 1-in-N} (config
 * {@code narrative.randomBookFromBookshelfOneIn}, default 50). On a hit the
 * plain book is swapped for one stamped Random Book rolled from
 * {@link RandomBookRegistry} via {@link RandomBookFactory#rollFromPool} — the
 * same call the {@code dungeontrain:random_book} loot placeholder uses in
 * {@code ContainerContentsRoller}. The total drop count is unchanged: each
 * conversion shrinks the plain stack by one and adds one narrative book.</p>
 *
 * <p>Deliberately narrow:</p>
 * <ul>
 *   <li>Only {@link Blocks#BOOKSHELF}. Silk Touch drops the block itself (no
 *       {@code minecraft:book} in the drops → natural no-op), and chiseled
 *       bookshelves are excluded so player-stored books are never converted.</li>
 *   <li>Only player-broken blocks — an explosion or piston shouldn't mint lore.</li>
 *   <li>Only {@code minecraft:book} stacks within the drop list.</li>
 * </ul>
 *
 * <p>A freshly-rolled Random Book carries no {@link RandomBookTag#NBT_HELD held}
 * marker, so {@link BurnableBookTag} treats it as non-burnable until a player
 * has actually held it. The dropped book therefore lands on the ground and can
 * be picked up — exactly like a random book falling out of a broken pot — and
 * only becomes burn-on-drop once held (via {@code NarrativeBookEvents}).</p>
 *
 * <p>Sable parity: new {@link ItemEntity ItemEntities} inherit the replaced
 * drop's position and velocity, so they ride a moving Sable carriage
 * identically to the books they stand in for. {@link BlockDropsEvent} hands us
 * the drops before they are spawned — adding to its list is sufficient
 * ({@code CommonHooks.handleBlockDrops} calls {@code addFreshEntity} on every
 * entry after the event), so this handler never spawns entities itself.</p>
 */
public final class RandomBookDropEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RandomBookDropEvents() {}

        public static void onBlockDrops(net.minecraft.server.level.ServerLevel dropsLevel, net.minecraft.core.BlockPos dropsPos, net.minecraft.world.level.block.state.BlockState dropsState, net.minecraft.world.entity.Entity dropsBreaker, java.util.List<net.minecraft.world.entity.item.ItemEntity> dropsList) {
        if (!dropsState.is(Blocks.BOOKSHELF)) return;
        if (!(dropsBreaker instanceof Player)) return;

        int oneIn = DungeonTrainConfig.getRandomBookFromBookshelfOneIn();
        if (oneIn <= 0) return; // disabled

        ServerLevel level = dropsLevel;
        RandomSource rng = level.getRandom();
        List<ItemEntity> drops = dropsList;

        // Collect mutations and apply them after iterating, so we never modify
        // `drops` while looping over it.
        List<ItemEntity> emptied = new ArrayList<>();
        List<ItemEntity> narrativeBooks = new ArrayList<>();

        for (ItemEntity drop : new ArrayList<>(drops)) {
            ItemStack stack = drop.getItem();
            if (!stack.is(Items.BOOK)) continue;

            int converted = 0;
            int count = stack.getCount();
            for (int i = 0; i < count; i++) {
                if (rng.nextInt(oneIn) != 0) continue;
                Optional<ItemStack> book = RandomBookFactory.rollFromPool(rng.nextLong());
                if (book.isEmpty()) continue; // empty pool — leave this book plain

                ItemEntity narrative = new ItemEntity(level,
                    drop.getX(), drop.getY(), drop.getZ(), book.get());
                narrative.setDeltaMovement(drop.getDeltaMovement());
                narrativeBooks.add(narrative);
                converted++;
            }

            if (converted == 0) continue;

            // Keep total drop count unchanged: shrink the plain stack by the
            // number we replaced. Build a new stack rather than mutating in
            // place (immutable style); drop the now-empty original.
            ItemStack remaining = stack.copy();
            remaining.shrink(converted);
            if (remaining.isEmpty()) {
                emptied.add(drop);
            } else {
                drop.setItem(remaining);
            }
        }

        if (narrativeBooks.isEmpty()) return;
        drops.removeAll(emptied);
        drops.addAll(narrativeBooks);
        LOGGER.info("[DungeonTrain] RandomBook: bookshelf break at {} yielded {} narrative book(s) (1-in-{})",
            dropsPos, narrativeBooks.size(), oneIn);
    }
}
