package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.stage.BlockFamilySpelling;
import games.brennan.dungeontrain.block.stage.StageStoneFamily;
import games.brennan.dungeontrain.block.stage.StageWoodFamily;
import games.brennan.dungeontrain.block.stage.StageWoodFamily.WoodKind;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.StagePalette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Derives a stage's {@link StagePalette} — what every stage placeholder block resolves to — from
 * the stage's block usage tally ({@link StageBlockIndex#blocksForStage}, most-used first) and
 * persists it on the {@link Stage} record via {@link StageStore}.
 *
 * <p>Runs when a stage is saved (the editor's stage commands + {@link StageDuplicator}), on demand
 * ({@code /dteditor stage bake}), and once at server start for every stage that has no palette yet
 * ({@link #bakeMissing}). The result is written to {@code stages.json} and is hand-editable — the
 * derivation below is a sensible first answer, not a contract.</p>
 *
 * <p>Derivation (pure — see {@link #derive}):</p>
 * <ul>
 *   <li><b>solid</b>: the tally's full opaque cubes, first {@value StagePalette#SOLID_SLOTS}; the
 *       reader loops shorter lists; {@code stone} when the stage has none.</li>
 *   <li><b>stairs / slabs</b>: the family variant of each solid slot ({@code X → X_stairs}, with the
 *       {@code _bricks → _brick_stairs}, {@code _tiles → _tile_stairs}, {@code _block → _stairs},
 *       {@code _planks → _stairs} spellings and aliases of {@link BlockFamilySpelling}); a solid with no variant walks down the solid list for the next that has
 *       one, then the most-used real stairs/slab in the tally, then stone.</li>
 *   <li><b>button / pressure plate</b>: the most-used one already in the tally; else the first
 *       solid's family (wood → that wood's, blackstone → polished blackstone's, else stone).</li>
 *   <li><b>wood</b>: the first tally block owned by any {@link StageWoodFamily}; else spruce.</li>
 *   <li><b>stone</b>: the first tally block owned by any {@link StageStoneFamily}; else stone.</li>
 * </ul>
 */
public final class StagePaletteBaker {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NS = "minecraft:";

    private StagePaletteBaker() {}

    // ---------------------------------------------------------------- entry points

    /** Bake + persist {@code stageId}. Returns the palette, or empty for an unknown stage. */
    public static Optional<StagePalette> bake(ServerLevel level, String stageId) {
        Optional<Stage> stage = StageStore.get(stageId);
        if (stage.isEmpty()) return Optional.empty();
        StagePalette palette = deriveFor(level, stage.get().id());
        persist(Map.of(stage.get().id(), palette));
        return Optional.of(palette);
    }

    /** Bake + persist every stage in the store (one write). Returns the number baked. */
    public static int bakeAll(ServerLevel level) {
        return bakeWhere(level, s -> true);
    }

    /**
     * Bake every stage that has no palette yet — the server-start bootstrap, which must run before
     * the first carriage is stamped so no placeholder resolves through the default palette.
     */
    public static int bakeMissing(ServerLevel level) {
        return bakeWhere(level, s -> s.palette() == null);
    }

    private static int bakeWhere(ServerLevel level, Predicate<Stage> which) {
        Map<String, StagePalette> baked = new LinkedHashMap<>();
        for (Stage stage : StageStore.allStages()) {
            if (which.test(stage)) baked.put(stage.id(), deriveFor(level, stage.id()));
        }
        if (baked.isEmpty()) return 0;
        persist(baked);
        return baked.size();
    }

    private static void persist(Map<String, StagePalette> palettes) {
        try {
            StageStore.savePalettes(palettes);
            LOGGER.info("[DungeonTrain] Baked stage palette(s) for {}.", palettes.keySet());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to persist baked stage palettes: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- derivation

    /** Palette for {@code stageId} from the live block index. */
    public static StagePalette deriveFor(ServerLevel level, String stageId) {
        List<String> tally = StageBlockIndex.blocksForStage(level, stageId).aggregatedBlockIds();
        return derive(tally, StagePaletteBaker::isFullSolidCube, StagePaletteBaker::exists);
    }

    /**
     * Pure derivation over a usage-ordered block-id tally. {@code solid} decides which ids may fill
     * a solid slot; {@code exists} answers whether a derived id is a real block (both injected so the
     * rule set is unit-testable without a registry).
     */
    public static StagePalette derive(List<String> tally, Predicate<String> solid, Predicate<String> exists) {
        List<String> solids = new ArrayList<>();
        for (String id : tally) {
            if (solid.test(id) && !solids.contains(id)) solids.add(id);
        }
        List<String> solidSlots = new ArrayList<>();
        for (int i = 0; i < Math.min(StagePalette.SOLID_SLOTS, solids.size()); i++) {
            solidSlots.add(solids.get(i));
        }

        List<String> stairs = variantSlots(solids, tally, "_stairs", StagePalette.STAIRS_SLOTS, exists);
        List<String> slabs = variantSlots(solids, tally, "_slab", StagePalette.SLAB_SLOTS, exists);

        String first = solids.isEmpty() ? null : solids.get(0);
        String button = firstEnding(tally, "_button")
            .orElseGet(() -> fittingFor(first, WoodKind.BUTTON, "_button", exists));
        String plate = firstEnding(tally, "_pressure_plate")
            .orElseGet(() -> fittingFor(first, WoodKind.PRESSURE_PLATE, "_pressure_plate", exists));

        String wood = StageWoodFamily.FALLBACK.id();
        for (String id : tally) {
            Optional<StageWoodFamily> f = StageWoodFamily.owning(id);
            if (f.isPresent()) { wood = f.get().id(); break; }
        }

        String stone = StageStoneFamily.FALLBACK.id();
        for (String id : tally) {
            Optional<StageStoneFamily> f = StageStoneFamily.owning(id, exists);
            if (f.isPresent()) { stone = f.get().id(); break; }
        }

        return new StagePalette(solidSlots, stairs, slabs, button, plate, wood, stone);
    }

    /**
     * Slot {@code i} is the {@code suffix} variant of solid {@code i}; a solid without one walks
     * down the remaining solids, then falls back to the most-used real {@code suffix} block in the
     * tally, then to the {@link StagePalette#DEFAULT} value (by leaving the slot list short).
     */
    private static List<String> variantSlots(List<String> solids, List<String> tally, String suffix,
                                             int slots, Predicate<String> exists) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            String found = null;
            for (int j = i; j < solids.size(); j++) {
                found = BlockFamilySpelling.variantOf(solids.get(j), suffix, exists);
                if (found != null) break;
            }
            if (found == null) found = firstEnding(tally, suffix).orElse(null);
            if (found == null) break;
            out.add(found);
        }
        return out;
    }

    /** Button / plate for a stage whose tally has none: from the first solid's family. */
    private static String fittingFor(String firstSolid, WoodKind kind, String suffix, Predicate<String> exists) {
        if (firstSolid != null) {
            Optional<StageWoodFamily> wood = StageWoodFamily.owning(firstSolid);
            if (wood.isPresent()) return wood.get().block(kind);
            if (firstSolid.contains("blackstone")) {
                String id = NS + "polished_blackstone" + suffix;
                if (exists.test(id)) return id;
            }
        }
        return NS + "stone" + suffix;
    }

    private static Optional<String> firstEnding(List<String> tally, String suffix) {
        for (String id : tally) {
            if (id.endsWith(suffix)) return Optional.of(id);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- registry-backed predicates

    /** A registered block whose default state is a full opaque cube (stone, planks, logs — not glass/leaves/stairs). */
    static boolean isFullSolidCube(String id) {
        Block block = lookup(id);
        if (block == null) return false;
        BlockState state = block.defaultBlockState();
        return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            && Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    static boolean exists(String id) {
        return lookup(id) != null;
    }

    private static Block lookup(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        return BuiltInRegistries.BLOCK.get(rl);
    }
}
