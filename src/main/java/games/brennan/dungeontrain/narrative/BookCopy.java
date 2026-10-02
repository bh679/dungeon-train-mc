package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Whether a written book is a crafted <b>copy</b> rather than an original.
 *
 * <p>Vanilla book cloning builds the copy from the original's whole stack and only swaps in a
 * {@link WrittenBookContent} one generation on, so a clone of a community book inherits every DT tag
 * the original carried — its pool id included, which is all the train's vote page looks for. The
 * generation is the one thing that tells the two apart: every book DT hands out is built at
 * generation 0 (see {@code BookFactory}), and a clone is always above it.</p>
 *
 * <p>A copy gets none of the train's page — no vote, no report, none of its writer's controls. The
 * client simply never offers it ({@code BookVoteClientEvents}) and the packet handlers refuse it, so
 * a modified client cannot bring it back.</p>
 */
public final class BookCopy {

    /** The generation DT's own books — and every freshly signed one — are built at. */
    private static final int ORIGINAL_GENERATION = 0;

    private BookCopy() {}

    /** True when {@code stack} is a written book cloned from another. False for anything else. */
    public static boolean isCopy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return content != null && isCopyGeneration(content.generation());
    }

    /** The rule itself: anything past the original generation is a copy. */
    static boolean isCopyGeneration(int generation) {
        return generation > ORIGINAL_GENERATION;
    }
}
