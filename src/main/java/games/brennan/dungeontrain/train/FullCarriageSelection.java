package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.SeededDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a carriage group is one <b>Full carriage</b> — a single {@link ContentsSize#FULL}
 * shell as long as the whole run, furnished with Full contents — and which shell.
 *
 * <p>The same shape as {@link WholeGroupSelection}: a seeded one-in-{@link FullCarriageSettings#every()
 * N} lottery per group ordinal, hashed rather than rolled so a re-stamped window gets the same
 * answer, salted with {@link #FULL_SALT} so it never lines up with the portal or whole-group draws.
 * Both of those win a collision — the caller asks this only when neither took the group.</p>
 *
 * <p>No Full shell (or none this world's dims can build) means "not a Full group": the run is placed
 * as ordinary carriages, so a world only sees Full carriages once someone has made one.</p>
 */
public final class FullCarriageSelection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long FULL_SALT = 0x46554C4C43415252L; // "FULLCARR"

    /** A shell chosen for a run, its template resolved at this world's Full box. */
    public record FullPick(CarriageVariant shell, StructureTemplate template) {}

    private FullCarriageSelection() {}

    /** The pure lottery — testable without a level. {@code every <= 0} is off. */
    public static boolean isFullGroup(int anchorPIdx, int groupSize, int every, long worldSeed) {
        if (every <= 0) return false;
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        return SeededDraw.hit(worldSeed ^ FULL_SALT, groupIndex, every);
    }

    /** The live verdict: settings, the session-only forced cadence, and the portal exclusion. */
    public static boolean isFullGroup(ServerLevel level, int anchorPIdx, int groupSize, long worldSeed) {
        if (PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) return false;
        int forced = FullCarriageSettings.forced();
        if (forced > 0) {
            long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
            return Math.floorMod(groupIndex, (long) forced) == 0L;
        }
        return isFullGroup(anchorPIdx, groupSize, FullCarriageSettings.every(), worldSeed);
    }

    /**
     * The Full shell for this run, or null when there is none to place — no Full shell with a
     * weight, every one gated out, or a Full box this world cannot build.
     */
    public static FullPick pick(ServerLevel level, int anchorPIdx, int groupSize, CarriageDims dims,
                                long worldSeed, GateContext anchorGate) {
        Optional<CarriageDims> box = ContentsSize.FULL.shellDims(dims, groupSize);
        if (box.isEmpty() || groupSize != DungeonTrainConfig.getGroupSize()) return null;
        CarriageWeights weights = CarriageWeights.current();
        List<String> fits = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (CarriagePlacer.sizeOf(v) != ContentsSize.FULL) continue;
            if (weights.weightFor(v.id()) <= 0) continue;
            if (anchorGate != null && !anchorGate.allows(weights.gateFor(v.id()))) continue;
            fits.add(v.id());
        }
        if (fits.isEmpty()) {
            LOGGER.info("[DungeonTrain] full carriage anchorPIdx={} → NO_SHELL", anchorPIdx);
            return null;
        }
        int groupIndex = (int) Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        String chosen = WholeCarriageSelection.weightedSeededPick(worldSeed ^ FULL_SALT, groupIndex, fits,
            weights::weightFor);
        CarriageVariant shell = CarriageVariantRegistry.find(chosen).orElse(null);
        if (shell == null) return null;
        StructureTemplate template = CarriageTemplateStore.get(level, shell, box.get()).orElse(null);
        if (template == null) {
            LOGGER.warn("[DungeonTrain] full carriage anchorPIdx={} → shell '{}' has no {}-long template",
                anchorPIdx, chosen, box.get().length());
            return null;
        }
        LOGGER.info("[DungeonTrain] full carriage anchorPIdx={} → FULL_HIT shell={}", anchorPIdx, chosen);
        return new FullPick(shell, template);
    }

    /**
     * Stamp the shell over the whole run at {@code runOrigin} and lay its variant blocks, then return
     * the per-carriage footprint for the shipyard. The contents follow after assembly, at shipyard
     * coords, like every other carriage's ({@link CarriagePlacer#applyContentsBlocksAt}).
     */
    public static Set<BlockPos> place(ServerLevel level, BlockPos runOrigin, FullPick pick, CarriageDims dims,
                                      int groupSize, long seed, int anchorPIdx) {
        return CarriageStampGuard.call(() -> {
            CarriagePlacer.stampTemplateAt(level, runOrigin, pick.template(), /*relight*/ false);
            CarriagePlacer.applyVariantBlocks(level, runOrigin, pick.shell(), dims, seed, anchorPIdx);
            Set<BlockPos> placed = new java.util.HashSet<>();
            for (int i = 0; i < Math.max(1, groupSize); i++) {
                placed.addAll(CarriagePlacer.collectFootprint(level, runOrigin.offset(i * dims.length(), 0, 0), dims));
            }
            return placed;
        });
    }
}
