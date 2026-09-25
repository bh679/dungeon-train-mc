package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps William Wythers' Overhauled Overworld's {@code minecraft:} tag edits out of the rest of the
 * world. Tags are global — they can't follow the {@code ow:wwoo} stretch the way
 * {@link VanillaBiomeFeatures} and {@link VanillaBiomeTwins} do — so the edits no WWOO feature
 * relies on are dropped at tag load (see {@code TagLoaderWwooFilterMixin}), as if WWOO never
 * shipped them. Other packs' contributions to the same tags are untouched.
 *
 * <p>Kept on purpose, because WWOO's own features need them inside its stretch:
 * {@code sand} (+packed mud, its badlands), {@code mushroom_grow_block} (+mushroom stem) and the
 * two {@code mangrove_*_can_grow_through} tags (its jungle/bayou trees).</p>
 *
 * <p>WWOO's packs are Cristel Lib built-ins registered at {@code Pack.Position.TOP} with ids like
 * {@code wwoo:resources/wwoo_main} — above DT's own resources, which is why a plain override file
 * can't win against their {@code "replace": true}.</p>
 */
public final class WwooTagFilter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WWOO_PACK_PREFIX = "wwoo:";

    /** Tag files (as the tag loader lists them) whose WWOO copy is ignored. */
    static final Set<ResourceLocation> UNDONE_TAG_FILES = Set.of(
            // replace:true → only barrier; snow layers would settle on ice and packed ice
            tagFile("block/snow_layer_cannot_survive_on"),
            // replace:true pair that swaps frog colours by biome (and drops the Nether from warm)
            tagFile("worldgen/biome/spawns_warm_variant_frogs"),
            tagFile("worldgen/biome/spawns_cold_variant_frogs"),
            // +smooth_basalt; deepslate ores would eat geode shells
            tagFile("block/deepslate_ore_replaceables"),
            // +bamboo/bamboo blocks/mangrove roots → #logs; leaves beside them would never decay
            tagFile("block/mangrove_logs"));

    private WwooTagFilter() {
    }

    private static ResourceLocation tagFile(String path) {
        return ResourceLocation.withDefaultNamespace("tags/" + path + ".json");
    }

    /** True when {@code fileId} is one of the undone tags and {@code packId} is one of WWOO's packs. */
    public static boolean shouldDrop(ResourceLocation fileId, String packId) {
        return packId != null && packId.startsWith(WWOO_PACK_PREFIX) && UNDONE_TAG_FILES.contains(fileId);
    }

    /**
     * A copy of {@code stacks} (tag file id → that file from each pack, lowest priority first) with
     * WWOO's copies of the undone tags removed. Returns {@code stacks} itself when nothing matches.
     */
    public static <R> Map<ResourceLocation, List<R>> filter(Map<ResourceLocation, List<R>> stacks,
                                                             Function<R, String> packIdOf) {
        if (UNDONE_TAG_FILES.stream().noneMatch(stacks::containsKey)) return stacks;
        Map<ResourceLocation, List<R>> out = new LinkedHashMap<>(stacks);
        for (ResourceLocation fileId : UNDONE_TAG_FILES) {
            List<R> stack = stacks.get(fileId);
            if (stack == null) continue;
            List<R> kept = new ArrayList<>(stack.size());
            for (R resource : stack) {
                String packId = packIdOf.apply(resource);
                if (shouldDrop(fileId, packId)) {
                    LOGGER.info("[DungeonTrain] Ignoring {} from {} (kept out of the world outside its stretch)",
                            fileId, packId);
                } else {
                    kept.add(resource);
                }
            }
            if (kept.size() != stack.size()) out.put(fileId, List.copyOf(kept));
        }
        return out;
    }
}
