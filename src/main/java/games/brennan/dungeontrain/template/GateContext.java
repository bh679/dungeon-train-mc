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
     * band classifiers ({@link TrainPhase#phaseAt}). The Overworld↔Nether <em>block-level</em> fade
     * for tunnels/tracks is applied later, at stamp time, via
     * {@link games.brennan.dungeontrain.worldgen.NetherFade} — not by softening this phase.
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
     * {@code seedAnchor = floorDiv(initialPIdx, groupSize) * groupSize}). This is the
     * <b>carriage-index</b> frame — used for the Diff-Level so a group's gate matches what the
     * boarding HUD, mob difficulty, and contents difficulty compute from
     * {@link DifficultyProgression#tierForTravelled tierForTravelled(carriageIndex)}.
     * {@code groupSize <= 0} is treated as 1 (per-carriage).
     */
    public static int groupAnchorPIdx(int carriagePIdx, int groupSize) {
        int g = Math.max(1, groupSize);
        return Math.floorDiv(carriagePIdx, g) * g;
    }

    /**
     * The <b>real overworld world-X</b> of the first enclosed carriage of the sub-level group
     * containing {@code carriagePIdx} (train origin X = 0). Each group physically occupies
     * {@code subLevelStride = groupSize × length + 2 × halfPadLen} blocks
     * ({@code halfPadLen = (length+1)/2}) because of the half-flatbed pads <b>between</b> groups, so
     * group {@code G} starts at {@code G × subLevelStride} — matching the assembler / appender
     * placement and therefore the frame the track / tunnel gates and the disintegration / nether band
     * terrain live in. This is NOT the pad-free {@code pIdx × length} frame, which lags real-X by
     * {@code groupIdx × 2 × halfPadLen} (≈1000 blocks by the Nether at the default length 9) — the
     * reason carriages gated on {@code pIdx × length} entered the Nether band ~1000 blocks late.
     * {@code groupSize <= 1} ⇒ single-carriage sub-levels with stride = length (no pads). Pure in the
     * three args.
     */
    public static int groupRealStartX(int carriagePIdx, int groupSize, int carriageLength) {
        int g = Math.max(1, groupSize);
        int halfPadLen = (carriageLength + 1) / 2;
        int subLevelStride = (g > 1) ? (g * carriageLength + 2 * halfPadLen) : carriageLength;
        return Math.floorDiv(carriagePIdx, g) * subLevelStride;
    }

    /**
     * Resolve the context for the carriage <b>group</b> containing {@code carriagePIdx}, gating the
     * whole group from one position so a connected group never themes half-Overworld / half-Nether.
     * The two axes deliberately use different frames:
     * <ul>
     *   <li><b>Diff-Level</b> from the group's <em>carriage-index</em> anchor
     *       ({@link #groupAnchorPIdx}) — stays consistent with the boarding HUD / mob / contents
     *       difficulty, all of which key on the carriage index.</li>
     *   <li><b>{@link TrainPhase Dimension}</b> from the group's <em>real overworld</em> X
     *       ({@link #groupRealStartX}) — the same frame the track / tunnel gates and the band terrain
     *       use, so a carriage's dimension flips at the same world-X as the track beneath it (the
     *       pad-free {@code pIdx × length} frame lagged the real frame by the inter-group pads).</li>
     * </ul>
     * Group size is read from this world's generation config. Pure in
     * {@code (carriagePIdx, groupSize, carriageLength)}.
     */
    public static GateContext forCarriage(ServerLevel level, int carriagePIdx, int carriageLength) {
        int groupSize = DungeonTrainWorldData.get(level.getServer().overworld()).getGenerationConfig().groupSize();
        int diffLevel = DifficultyProgression.tierForTravelled(groupAnchorPIdx(carriagePIdx, groupSize));
        TrainPhase phase = TrainPhase.phaseAt(level.getServer().overworld(),
            groupRealStartX(carriagePIdx, groupSize, carriageLength));
        return new GateContext(diffLevel, phase);
    }
}
