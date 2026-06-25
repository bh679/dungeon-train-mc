package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.server.level.ServerLevel;

/**
 * The resolved spawn context at a template-selection site: the Diff-Level and worldgen
 * {@link TrainPhase} of the column a template is being placed in. The generator builds one of these
 * per carriage / track tile and uses {@link #allows(TemplateGate)} to drop out-of-band /
 * out-of-phase candidates from the weighted pool <b>before</b> the weighted pick — the same shape
 * as the mob difficulty-band drop, one layer up.
 *
 * <p>Both fields are pure functions of position (carriage pIdx / world-X) + live config, so the
 * gated pool is deterministic and reproducible across reloads, and the block pass and deferred
 * entity pass of the same carriage resolve an identical context without sharing extra state. A
 * {@code null} {@code GateContext} (passed by editor previews / tests) means "no gating".</p>
 */
public record GateContext(int level, TrainPhase phase) {

    /** True iff {@code gate} admits this context's Diff-Level and phase. */
    public boolean allows(TemplateGate gate) {
        return gate.eligible(level, phase);
    }

    /**
     * Resolve the context for the column at {@code worldX}. {@code carriageLength} maps the world-X
     * to the carriage-equivalent index for the Diff-Level (see
     * {@link DifficultyProgression#levelAtWorldX}); the phase is taken straight from the world-X
     * band classifiers.
     */
    public static GateContext atWorldX(ServerLevel level, int worldX, int carriageLength) {
        ServerLevel overworld = level.getServer().overworld();
        int diffLevel = DifficultyProgression.levelAtWorldX(worldX, carriageLength);
        return new GateContext(diffLevel, TrainPhase.phaseAt(overworld, worldX));
    }

    /**
     * {@link #atWorldX(ServerLevel, int, int)} resolving the carriage length from this world's
     * generation dims — convenience for the track / pillar / tunnel worldgen call sites that don't
     * already have a {@code CarriageDims} in scope.
     */
    public static GateContext atWorldX(ServerLevel level, int worldX) {
        int carriageLength = DungeonTrainWorldData.get(level.getServer().overworld()).dims().length();
        return atWorldX(level, worldX, carriageLength);
    }

    /**
     * Resolve the context for carriage {@code carriagePIdx}: its Diff-Level is
     * {@code tierForTravelled(abs(pIdx))} (identical to {@link DifficultyProgression#levelAtWorldX}
     * at {@code pIdx*carriageLength}) and its phase is taken at world-X
     * {@code pIdx*carriageLength} — the placement X the band terrain was generated against (the
     * train is anchored at world-X 0). Pure in {@code carriagePIdx}, so the block pass and deferred
     * entity pass agree.
     */
    public static GateContext forCarriage(ServerLevel level, int carriagePIdx, int carriageLength) {
        return atWorldX(level, carriagePIdx * carriageLength, carriageLength);
    }
}
