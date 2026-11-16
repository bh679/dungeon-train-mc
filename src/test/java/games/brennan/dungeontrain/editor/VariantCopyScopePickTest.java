package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy-scoped cell is empty where it does not apply. It used to be left to the room template,
 * which holds whatever entry the editor preview showed at save time, so a "Not copies" stage block
 * landed in every copy.
 */
final class VariantCopyScopePickTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final VariantState ROLLED = VariantState.of(Blocks.STONE.defaultBlockState());

    private static VariantState pick(VariantCopyScope scope, boolean baseTile, AtomicInteger calls) {
        Supplier<VariantState> roll = () -> {
            calls.incrementAndGet();
            return ROLLED;
        };
        return scope.pickFor(baseTile, roll);
    }

    @Test
    @DisplayName("Both rolls in the arrival room and in every copy")
    void bothAlwaysRolls() {
        AtomicInteger calls = new AtomicInteger();
        assertSame(ROLLED, pick(VariantCopyScope.BOTH, true, calls));
        assertSame(ROLLED, pick(VariantCopyScope.BOTH, false, calls));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Not copies rolls in the arrival room and is empty in copies, without rolling")
    void notCopiesIsEmptyInCopies() {
        AtomicInteger calls = new AtomicInteger();
        assertSame(ROLLED, pick(VariantCopyScope.NOT_COPIES, true, calls));
        VariantState inCopy = pick(VariantCopyScope.NOT_COPIES, false, calls);
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(inCopy.state()));
        assertEquals(1, calls.get(), "an excluded cell must not draw from the roll");
    }

    @Test
    @DisplayName("Copies is empty in the arrival room, without rolling, and rolls in copies")
    void copiesIsEmptyInArrivalRoom() {
        AtomicInteger calls = new AtomicInteger();
        VariantState inBase = pick(VariantCopyScope.COPIES, true, calls);
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(inBase.state()));
        assertEquals(0, calls.get());
        assertSame(ROLLED, pick(VariantCopyScope.COPIES, false, calls));
        assertEquals(1, calls.get());
    }
}
