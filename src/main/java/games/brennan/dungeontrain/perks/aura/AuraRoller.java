package games.brennan.dungeontrain.perks.aura;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Rolls an aura's draws forward to a shape: new effects until the shape's count is met (the first from
 * the def's first pool, each later one from what is left), then a pick for each new level round. A level
 * round raises a random switched-on effect that takes levels, among those at the lowest level, so every
 * one reaches II before any reaches III; with none, the round is spent ({@code -1}). Earlier rolls never
 * change, so a lower rank later in the same life reads a prefix of them.
 */
public final class AuraRoller {

    private AuraRoller() {}

    /** {@code nextInt.applyAsInt(bound)} returns a random int in {@code [0, bound)}. */
    public static AuraDraws extend(AuraDef def, AuraDraws draws, AuraLadder.Shape shape, Set<ResourceLocation> off,
                                   IntUnaryOperator nextInt) {
        AuraDraws out = draws;
        while (out.effects().size() < shape.count()) {
            List<ResourceLocation> candidates = new ArrayList<>(out.effects().isEmpty() ? def.firstCandidates() : def.pool());
            candidates.removeAll(out.effects());
            if (candidates.isEmpty()) break;
            out = out.withEffect(candidates.get(nextInt.applyAsInt(candidates.size())));
        }
        while (out.levelUps().size() < shape.levelUps()) {
            out = out.withLevelUp(pickLevelUp(def, out, Math.min(shape.count(), out.effects().size()), off, nextInt));
        }
        return out;
    }

    private static int pickLevelUp(AuraDef def, AuraDraws draws, int count, Set<ResourceLocation> off, IntUnaryOperator nextInt) {
        int rounds = draws.levelUps().size();
        int lowest = Integer.MAX_VALUE;
        List<Integer> best = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation e = draws.effects().get(i);
            if (off.contains(e) || def.noLevels().contains(e)) continue;
            int level = draws.level(i, rounds);
            if (level < lowest) {
                lowest = level;
                best.clear();
            }
            if (level == lowest) best.add(i);
        }
        return best.isEmpty() ? -1 : best.get(nextInt.applyAsInt(best.size()));
    }
}
