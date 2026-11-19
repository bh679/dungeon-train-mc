package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ore-blob shortcut only understands rules whose miss draws nothing, and draws what doPlace would. */
final class OreTargetPrecheckTest {

    private static final BlockState ORE = Blocks.MAGMA_BLOCK.defaultBlockState();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("block, tag and state targets combine into one predicate")
    void deterministicRulesCombine() {
        Predicate<BlockState> p = OreTargetPrecheck.targetPredicate(List.of(
                OreConfiguration.target(new BlockMatchTest(Blocks.SOUL_SAND), ORE),
                OreConfiguration.target(new TagMatchTest(BlockTags.BASE_STONE_NETHER), ORE),
                OreConfiguration.target(new BlockStateMatchTest(Blocks.GRAVEL.defaultBlockState()), ORE)));
        assertNotNull(p);
        assertTrue(p.test(Blocks.SOUL_SAND.defaultBlockState()));
        // Tags aren't bound in a unit test, so the tag target matches nothing here; it must still be accepted.
        assertTrue(p.test(Blocks.GRAVEL.defaultBlockState()));
        assertFalse(p.test(Blocks.AIR.defaultBlockState()));
        assertFalse(p.test(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("an always-true (or unknown) target means the blob is never skipped")
    void unknownRuleNeverSkips() {
        assertNull(OreTargetPrecheck.targetPredicate(List.of(
                OreConfiguration.target(new BlockMatchTest(Blocks.SOUL_SAND), ORE),
                OreConfiguration.target(AlwaysTrueTest.INSTANCE, ORE))));
    }

    @Test
    @DisplayName("a skipped blob draws one double per point, as doPlace does before touching blocks")
    void skipDrawsBlobPoints() {
        RandomSource skipped = RandomSource.create(42L);
        RandomSource reference = RandomSource.create(42L);
        OreTargetPrecheck.drawBlobPoints(skipped, 64);
        for (int i = 0; i < 64; i++) reference.nextDouble();
        assertEquals(reference.nextLong(), skipped.nextLong());
    }
}
