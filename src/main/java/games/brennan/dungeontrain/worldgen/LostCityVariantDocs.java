package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.building.BuildingStore;
import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The block-variant documents of DT's own worldgen buildings: {@code <name>.variants.json} beside the
 * building's {@code .nbt}, in the schema every other variants sidecar uses
 * ({@link TrackVariantBlocks#fromJsonText}). Loaded through the resource manager, so a datapack can
 * replace one and {@code /reload} picks it up, and published as an immutable map the worldgen threads
 * only read.
 *
 * <p>A building with no document has no entry, and {@link LostCityVariantsProcessor} leaves it exactly
 * as it generates today.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LostCityVariantDocs {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SUFFIX = ".variants.json";
    private static final String STRUCTURES = "structure/";
    /** Template folders that may carry a document — DT's Lost City buildings and player-added ones. */
    private static final List<String> ROOTS = List.of("structure/lost_city", "structure/buildings");

    private static volatile Map<ResourceLocation, Doc> docs = Map.of();

    /** One building's pools, with its cells as a set for the per-block lookup. */
    public record Doc(TrackVariantBlocks blocks, Set<BlockPos> cells) {
        boolean has(BlockPos local) {
            return cells.contains(local);
        }
    }

    private LostCityVariantDocs() {}

    /**
     * The document for the template {@code dungeontrain:lost_city/<name>}, or {@code null} — also when a
     * player's own copy of the building replaces the jar's ({@code StructureTemplateManagerBuildingMixin}):
     * the shipped document's cells belong to the shipped template, not to what the player built.
     */
    @Nullable
    static Doc get(ResourceLocation template) {
        Doc doc = docs.get(template);
        if (doc == null) return null;
        return OVERRIDDEN.computeIfAbsent(template, LostCityVariantDocs::overridden) ? null : doc;
    }

    /**
     * Whether a building has a player copy, per template. Cached as the template manager caches the copy, and
     * dropped with it ({@link #forget}, from {@code BuildingWorldgen.evict}) — the processor asks once per block.
     */
    private static final Map<ResourceLocation, Boolean> OVERRIDDEN = new ConcurrentHashMap<>();

    /** Whether the player has their own copy of a building; swapped in tests. */
    static volatile Predicate<String> playerCopy = BuildingStore::hasPlayerCopy;

    private static boolean overridden(ResourceLocation template) {
        boolean overridden = Buildings.nameOf(template).map(playerCopy::test).orElse(false);
        if (overridden) {
            LOGGER.info("[DungeonTrain] Building variants: {} is the player's own copy; its shipped variants are not rolled",
                    template);
        }
        return overridden;
    }

    /** Drop what is known about {@code name}'s player copy — after a save, reset or delete of that building. */
    public static void forget(String name) {
        OVERRIDDEN.remove(Buildings.shippedTemplateId(name));
        OVERRIDDEN.remove(Buildings.playerTemplateId(name));
    }

    /** Drop what is known about every building's player copy — a package switch or template reload. */
    public static void forgetAll() {
        OVERRIDDEN.clear();
    }

    /** Replaces the published set (the reload listener, and tests). */
    static void publish(Map<ResourceLocation, Doc> next) {
        docs = Map.copyOf(next);
        forgetAll();
    }

    /** Parses one document; a document with no usable cell is {@code null}. */
    @Nullable
    public static Doc parse(ResourceLocation template, String json) {
        TrackVariantBlocks blocks = TrackVariantBlocks.fromJsonText(json, null, template.toString(), null);
        if (blocks.isEmpty()) return null;
        Set<BlockPos> cells = new HashSet<>();
        for (CarriageVariantBlocks.Entry entry : blocks.entries()) cells.add(entry.localPos());
        return new Doc(blocks, Set.copyOf(cells));
    }

    /** The template id a document resource belongs to: {@code structure/lost_city/a.variants.json} → {@code lost_city/a}. */
    static ResourceLocation templateOf(ResourceLocation resource) {
        String path = resource.getPath();
        return ResourceLocation.fromNamespaceAndPath(resource.getNamespace(),
                path.substring(STRUCTURES.length(), path.length() - SUFFIX.length()));
    }

    static void load(ResourceManager resources) {
        Map<ResourceLocation, Doc> next = new HashMap<>();
        for (String root : ROOTS) {
            for (Map.Entry<ResourceLocation, Resource> found
                    : resources.listResources(root, id -> id.getPath().endsWith(SUFFIX)).entrySet()) {
                ResourceLocation template = templateOf(found.getKey());
                try (Reader reader = found.getValue().openAsReader()) {
                    Doc doc = parse(template, readAll(reader));
                    if (doc != null) next.put(template, doc);
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("[DungeonTrain] Building variants {} failed to load; that building generates without them",
                            found.getKey(), e);
                }
            }
        }
        publish(next);
        LOGGER.info("[DungeonTrain] Building variants: {} document(s) loaded", next.size());
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        for (int n; (n = reader.read(buffer)) >= 0; ) text.append(buffer, 0, n);
        return text.toString();
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:building_variants";
            }
        });
    }
}
