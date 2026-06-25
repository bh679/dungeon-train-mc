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
     * The sub-level <b>group anchor</b> pIdx for {@code carriagePIdx}: the lowest pIdx in the Sable
     * sub-level group of {@code groupSize} enclosed carriages that contains it. Mirrors the
     * assembler's tiling ({@code TrainAssembler}'s
     * {@code seedAnchor = floorDiv(initialPIdx, groupSize) * groupSize}) — the half-flatbed pads sit
     * outside the integer carriage grid, so pIdx-space tiles cleanly into blocks of {@code groupSize}.
     * {@code groupSize <= 0} is treated as 1 (per-carriage).
     */
    public static int groupAnchorPIdx(int carriagePIdx, int groupSize) {
        int g = Math.max(1, groupSize);
        return Math.floorDiv(carriagePIdx, g) * g;
    }

    /**
     * Resolve the context for the carriage <b>group</b> containing {@code carriagePIdx}: every car in
     * the same sub-level group resolves the <em>same</em> Diff-Level and {@link TrainPhase} from the
     * group's anchor world-X ({@code groupAnchorPIdx * carriageLength} — the placement X the band
     * terrain was generated against, train anchored at world-X 0). Gating the whole group from one
     * overworld position means a connected group never themes half-Overworld / half-Nether when it
     * straddles a band edge. The group size is read from this world's generation config — the same
     * source the assembler tiles sub-levels by — so the shell, contents, and parts gates (and the
     * block pass + deferred entity pass) all agree for a given carriage. Pure in
     * {@code (carriagePIdx, groupSize, carriageLength)}.
     */
    public static GateContext forCarriage(ServerLevel level, int carriagePIdx, int carriageLength) {
        int groupSize = DungeonTrainWorldData.get(level.getServer().overworld()).getGenerationConfig().groupSize();
        int anchorPIdx = groupAnchorPIdx(carriagePIdx, groupSize);
        return atWorldX(level, anchorPIdx * carriageLength, carriageLength);
    }
}
