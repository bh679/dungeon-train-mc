package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.mixin.RandomBlockMatchTestAccessor;
import games.brennan.dungeontrain.mixin.RandomBlockStateMatchTestAccessor;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

import java.util.List;
import java.util.function.Predicate;

/**
 * An exact shortcut for an ore blob that cannot replace anything: when no chunk section under its bounding box
 * holds a block any of its targets match, vanilla {@code OreFeature.doPlace} (1.21.1) changes nothing and only
 * draws {@code size} doubles (one per blob point). {@link #trySkip} does just that draw and reports the skip,
 * so the world and every later random draw come out identical.
 *
 * <p>Why it matters: Deeper Darker's Otherside biomes place hundreds of 48-64-block blobs per chunk aimed at
 * {@code sculk_grime} (soul soil 256, soul sand 192, magma 128, gloomy sculk 96 in overcast columns), but the
 * grime is only the sea below Otherside y 17. Most blobs land far above it and scanned every block of their
 * volume for nothing: ore placement was 30% of a Deeper Dark band's worker CPU. Applied by
 * {@code OreFeatureTargetSkipMixin} inside DT's core feature pass only ({@link NetherCoreDecoration}).</p>
 *
 * <p><b>Exactness rests on vanilla's draw order</b>: {@code doPlace} draws only those {@code size} doubles
 * plus, inside {@code canPlaceOre}, draws that happen after a target test matched. Only rule tests whose
 * {@code test} draws nothing on a non-matching block are understood here; any other rule (always_true, a mod's
 * own) means "never skip". Recheck {@code OreFeature.doPlace} on a Minecraft or NeoForge bump.</p>
 */
public final class OreTargetPrecheck {

    private OreTargetPrecheck() {}

    /**
     * Skip the blob if nothing under its bounding box can be replaced, drawing exactly what {@code doPlace}
     * would have. The box is {@code doPlace}'s own ({@code x..x+width}, {@code y..y+height}, {@code z..z+width}).
     *
     * @return true when the blob was skipped (the caller returns {@code false} from {@code doPlace})
     */
    public static boolean trySkip(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                  int x, int y, int z, int width, int height) {
        Predicate<BlockState> target = targetPredicate(config.targetStates);
        if (target == null || !noTargetInBox(level, target, x, y, z, width, height)) return false;
        drawBlobPoints(random, config.size);
        return true;
    }

    /** The draws {@code doPlace} makes before touching any block: one {@code nextDouble} per blob point. */
    static void drawBlobPoints(RandomSource random, int size) {
        for (int i = 0; i < size; i++) random.nextDouble();
    }

    /**
     * Matches any block one of the targets could replace, or {@code null} when a target's rule isn't one whose
     * miss is known to draw nothing (the blob must then run normally). Exact classes only: a subclass may
     * override {@code test}.
     */
    static Predicate<BlockState> targetPredicate(List<OreConfiguration.TargetBlockState> targets) {
        Predicate<BlockState> any = null;
        for (OreConfiguration.TargetBlockState t : targets) {
            Predicate<BlockState> one = ruleMatch(t.target);
            if (one == null) return null;
            any = any == null ? one : any.or(one);
        }
        return any;
    }

    private static Predicate<BlockState> ruleMatch(RuleTest rule) {
        Class<?> type = rule.getClass();
        // These three never read the random source.
        if (type == BlockMatchTest.class || type == TagMatchTest.class || type == BlockStateMatchTest.class) {
            return state -> rule.test(state, null);
        }
        // These draw only after the block matched, so the block test alone decides a miss.
        if (type == RandomBlockMatchTest.class) {
            var block = ((RandomBlockMatchTestAccessor) rule).dungeontrain$block();
            return state -> state.is(block);
        }
        if (type == RandomBlockStateMatchTest.class) {
            BlockState wanted = ((RandomBlockStateMatchTestAccessor) rule).dungeontrain$blockState();
            return state -> state == wanted;
        }
        return null;
    }

    /**
     * True only when every chunk section the box reaches is present and its palette holds no target. A
     * palette may keep a state no longer placed (a false "maybe"), never the reverse, so a true is certain.
     * A chunk outside the region answers "can't tell".
     */
    static boolean noTargetInBox(WorldGenLevel level, Predicate<BlockState> target,
                                 int x, int y, int z, int width, int height) {
        int yLo = Math.max(y, level.getMinBuildHeight());
        int yHi = Math.min(y + height, level.getMaxBuildHeight() - 1);
        if (yLo > yHi) return true;                                      // all outside build height: nothing to replace
        int syLo = SectionPos.blockToSectionCoord(yLo);
        int syHi = SectionPos.blockToSectionCoord(yHi);
        for (int cx = SectionPos.blockToSectionCoord(x); cx <= SectionPos.blockToSectionCoord(x + width); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(z); cz <= SectionPos.blockToSectionCoord(z + width); cz++) {
                if (!level.hasChunk(cx, cz)) return false;
                ChunkAccess chunk = level.getChunk(cx, cz);
                for (int sy = syLo; sy <= syHi; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section.maybeHas(target)) return false;
                }
            }
        }
        return true;
    }
}
